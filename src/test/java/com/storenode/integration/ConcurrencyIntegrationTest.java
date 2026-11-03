package com.storenode.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.storenode.application.OrderService;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.order.Order;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyIntegrationTest extends IntegrationTest {

    private static final int TERMINALS = 50;

    @Autowired
    private OrderService orderService;

    @Test
    void fiftyTerminalsFightingOverTwentyUnitsNeverOversell() {
        UUID wrap = addProduct("WRAP", "100.00", 20);

        Result result = allAtOnce(TERMINALS, () -> orderService.placeOrder(command(wrap, 1)));

        assertThat(result.failures).isEmpty();
        assertThat(result.placed).isEqualTo(20);
        assertThat(result.outOfStock).isEqualTo(30);
        assertThat(stockOf(wrap)).isZero();
        assertThat(count("orders")).isEqualTo(20);
    }

    // ---- Scenario C ----

    @Test
    void theSameOrderSentFiftyTimesAtOnceIsPlacedExactlyOnce() {
        UUID wrap = addProduct("WRAP", "100.00", 100);
        Queue<String> orderIds = new ConcurrentLinkedQueue<>();

        // a nervous terminal (or 50 of them) sending one sale with one key, all at the same moment
        Result result = allAtOnce(TERMINALS, () -> {
            ResponseEntity<JsonNode> response = post(wrap, 1, "sale-42");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            orderIds.add(response.getBody().get("id").asText());
            return response;
        });

        assertThat(result.failures).isEmpty();
        assertThat(orderIds).hasSize(TERMINALS);
        assertThat(Set.copyOf(orderIds)).hasSize(1);   // everybody was told about the same order
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf(wrap)).isEqualTo(99);        // one unit, not fifty
        assertThat(jdbc.sql("select max(last_number) from fiscal_counter").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void ordersListingTheSameProductsInOppositeOrderDoNotDeadlock() {
        UUID a = addProduct("A", "10.00", 1000);
        UUID b = addProduct("B", "10.00", 1000);
        AtomicInteger counter = new AtomicInteger();

        // half the terminals list A then B, the other half B then A
        Result result = allAtOnce(40, () -> counter.getAndIncrement() % 2 == 0
                ? orderService.placeOrder(command(a, 1, b, 1))
                : orderService.placeOrder(command(b, 1, a, 1)));

        assertThat(result.failures).isEmpty();
        assertThat(result.placed).isEqualTo(40);
        assertThat(stockOf(a)).isEqualTo(960);
        assertThat(stockOf(b)).isEqualTo(960);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aCheckoutStuckBehindALockGivesUpAndAsksTheTerminalToRetry() throws Exception {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // someone else holds the wrap's stock row (a long report, a stuck process...)
        Thread holder = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("select quantity from inventory where product_id = :id for update").param("id", wrap).query().listOfRows();
            locked.countDown();
            await(release, 8);
        }));
        holder.start();
        locked.await();

        try {
            ResponseEntity<JsonNode> response = post(wrap, 1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().get("code").asText()).isEqualTo("TRY_AGAIN");
            assertThat(response.getHeaders().getFirst("Retry-After")).isNotNull();
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(stockOf(wrap)).isEqualTo(10);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void whenEveryConnectionIsBusyACheckoutAsksTheTerminalToRetryInsteadOfHanging() throws Exception {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
        List<Connection> taken = new ArrayList<>();
        try {
            for (int i = 0; i < poolSize; i++) {
                taken.add(dataSource.getConnection());
            }

            ResponseEntity<JsonNode> response = post(wrap, 1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().get("code").asText()).isEqualTo("TRY_AGAIN");
        } finally {
            for (Connection connection : taken) {
                connection.close();
            }
        }
        assertThat(stockOf(wrap)).isEqualTo(10);
    }

    // ---- Scenario B ----

    @Test
    void fiftyOrdersAtOnceGetFiftyDifferentNumbersWithNoGaps() {
        // a different product per terminal, so nothing else forces the orders to queue up
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < TERMINALS; i++) {
            products.add(addProduct("P" + i, "10.00", 5));
        }
        AtomicInteger next = new AtomicInteger();

        Result result = allAtOnce(TERMINALS, () -> orderService.placeOrder(command(products.get(next.getAndIncrement()), 1)));

        assertThat(result.failures).isEmpty();
        assertThat(result.placed).isEqualTo(TERMINALS);

        List<String> numbers = jdbc.sql("select order_number from orders order by order_number")
                .query(String.class).list();
        String prefix = numbers.get(0).substring(0, numbers.get(0).lastIndexOf('-') + 1);   // ORD-AIR01-20261103-
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= TERMINALS; i++) {
            expected.add(prefix + String.format("%04d", i));
        }
        assertThat(numbers).containsExactlyElementsOf(expected);
    }

    @Test
    void aLaterOrderNeverGetsASmallerNumber() {
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < TERMINALS; i++) {
            products.add(addProduct("Q" + i, "10.00", 5));
        }
        AtomicInteger next = new AtomicInteger();

        allAtOnce(TERMINALS, () -> orderService.placeOrder(command(products.get(next.getAndIncrement()), 1)));

        // read the orders back in the order they were placed: the numbers must go up and up
        List<String> byTime = jdbc.sql("select order_number from orders order by placed_at, order_number")
                .query(String.class).list();
        assertThat(byTime).isSorted();
    }

    @Test
    void anOrderThatFailsAfterGettingItsNumberDoesNotBurnTheNumber() {
        UUID boom = addProduct("BOOM", "10.00", 5);
        UUID fine = addProduct("FINE", "10.00", 5);

        // A trigger makes saving the BOOM order line fail. By then the order already has its number,
        // so this is a crash "in the middle" of checkout.
        jdbc.sql("""
            create function fail_on_boom() returns trigger as $$
            begin
                if new.sku = 'BOOM' then
                    raise exception 'simulated crash while saving';
                end if;
                return new;
            end
            $$ language plpgsql""").update();
        jdbc.sql("create trigger fail_on_boom before insert on order_line for each row execute function fail_on_boom()").update();
        try {
            assertThatThrownBy(() -> orderService.placeOrder(command(boom, 1))).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.sql("drop trigger fail_on_boom on order_line").update();
            jdbc.sql("drop function fail_on_boom()").update();
        }

        Order next = orderService.placeOrder(command(fine, 1));

        assertThat(next.number().format()).endsWith("-0001");   // the failed order left no hole
        assertThat(stockOf(boom)).isEqualTo(5);
    }




    // ---- helpers ----

    private static class Result {
        int placed;
        int outOfStock;
        final List<Throwable> failures = new ArrayList<>();
    }

    /** Runs the job on {@code threads} threads that all start at the same instant, and counts what happened. */
    private Result allAtOnce(int threads, Callable<?> job) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();      // "I'm at the starting line"
                go.await();             // wait for the starting gun
                return job.call();
            }));
        }

        Result result = new Result();
        try {
            ready.await();              // wait until all threads are at the line
            go.countDown();             // fire the gun
            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    result.placed++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof OutOfStockException) {
                        result.outOfStock++;
                    } else {
                        result.failures.add(e.getCause());   // anything unexpected is recorded
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private OrderService.PlaceOrderCommand command(UUID product, int quantity) {
        return new OrderService.PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new OrderService.PlaceOrderCommand.Item(product, new Quantity(quantity))),
                new Payment(PaymentMethod.CASH, Money.of("100000", "INR")));
    }

    private OrderService.PlaceOrderCommand command(UUID first, int firstQuantity, UUID second, int secondQuantity) {
        return new OrderService.PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new OrderService.PlaceOrderCommand.Item(first, new Quantity(firstQuantity)),
                        new OrderService.PlaceOrderCommand.Item(second, new Quantity(secondQuantity))),
                new Payment(PaymentMethod.CASH, Money.of("100000", "INR")));
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ResponseEntity<JsonNode> post(UUID product, int quantity) {
        return post(product, quantity, UUID.randomUUID().toString());
    }

    private ResponseEntity<JsonNode> post(UUID product, int quantity, String idempotencyKey) {
        String json = "{\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}],"
                + "\"payment\":{\"method\":\"CASH\",\"amount\":\"1000\",\"currency\":\"INR\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", idempotencyKey);
        return http.postForEntity("/api/v1/orders", new HttpEntity<>(json, headers), JsonNode.class);
    }


}
