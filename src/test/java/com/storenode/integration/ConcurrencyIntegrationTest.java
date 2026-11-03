package com.storenode.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.storenode.application.OrderService;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


import static org.assertj.core.api.Assertions.assertThat;

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
        return new OrderService.PlaceOrderCommand("KIOSK-07",
                List.of(new OrderService.PlaceOrderCommand.Item(product, new Quantity(quantity))),
                new Payment(PaymentMethod.CASH, Money.of("100000", "INR")));
    }

    private OrderService.PlaceOrderCommand command(UUID first, int firstQuantity, UUID second, int secondQuantity) {
        return new OrderService.PlaceOrderCommand("KIOSK-07",
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
        String json = "{\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}],"
                + "\"payment\":{\"method\":\"CASH\",\"amount\":\"1000\",\"currency\":\"INR\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/orders", new HttpEntity<>(json, headers), JsonNode.class);
    }


}
