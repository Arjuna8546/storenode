package com.storenode.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.storenode.application.OrderService;
import com.storenode.application.OrderService.PlaceOrderCommand;
import com.storenode.application.OutboxDispatcher;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario D. The outbox poller is switched off in the test profile, so these tests call the dispatcher
 * themselves and decide exactly when "later" happens.
 */
class KitchenIntegrationTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anOrderWithFoodToPrepareReachesTheKitchenOnce() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);

        ResponseEntity<JsonNode> placed = place(meal, 2);

        // checkout is finished, but the kitchen has not been told yet: the event is waiting in the outbox
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(outboxStatuses()).containsExactly("PENDING");
        assertThat(count("kitchen_ticket")).isZero();

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(outboxStatuses()).containsExactly("PUBLISHED");
        JsonNode tickets = http.getForEntity("/api/v1/kitchen/tickets", JsonNode.class).getBody();
        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).get("orderNumber").asText()).isEqualTo(placed.getBody().get("orderNumber").asText());
        assertThat(tickets.get(0).get("items").get(0).get("sku").asText()).isEqualTo("MEAL");
        assertThat(tickets.get(0).get("items").get(0).get("quantity").asInt()).isEqualTo(2);

        assertThat(dispatcher.dispatchBatch()).isZero();   // nothing is left to do
    }

    @Test
    void packagedFoodNeverReachesTheKitchen() {
        UUID water = addProduct("WATER", "40.00", 10, false);

        place(water, 1);

        assertThat(count("outbox_event")).isZero();
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertThat(count("kitchen_ticket")).isZero();
    }

    @Test
    void aBrokenKitchenNeverStopsCheckoutAndNothingIsLost() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        breakTheKitchen();
        try {
            // three customers order while the kitchen screen is down: every checkout still succeeds
            for (int i = 0; i < 3; i++) {
                assertThat(place(meal, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }

            assertThat(dispatcher.dispatchBatch()).isEqualTo(3);   // three attempts, three failures

            assertThat(count("orders")).isEqualTo(3);
            assertThat(count("kitchen_ticket")).isZero();
            assertThat(outboxStatuses()).containsExactly("PENDING", "PENDING", "PENDING");   // still waiting, not lost
            assertThat(attemptsOfEveryEvent()).containsExactly(1, 1, 1);
            assertThat(dispatcher.dispatchBatch()).isZero();        // they wait for their back-off time
        } finally {
            repairTheKitchen();
        }

        makeEverythingDue();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(3);

        assertThat(count("kitchen_ticket")).isEqualTo(3);
        assertThat(outboxStatuses()).containsExactly("PUBLISHED", "PUBLISHED", "PUBLISHED");
    }

    @Test
    void anOrderWaitingInTheOutboxIsStillDeliveredAfterARestart() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        place(meal, 1);
        // The node dies here. Everything it knew was in the database, so a restart loses nothing:
        // the first dispatch of the "new" process finds the event and delivers it.

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(count("kitchen_ticket")).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceStillMakesOneTicket() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        place(meal, 1);
        dispatcher.dispatchBatch();

        // as if the "published" mark had been lost in a crash: the event looks undelivered again
        jdbc.sql("update outbox_event set status = 'PENDING', next_attempt_at = :due").param("due", longAgo()).update();
        dispatcher.dispatchBatch();

        assertThat(count("kitchen_ticket")).isEqualTo(1);
        assertThat(outboxStatuses()).containsExactly("PUBLISHED");
    }

    @Test
    void anOrderThatFailsToSaveLeavesNoKitchenEventBehind() {
        UUID meal = addProduct("MEAL-BOOM", "100.00", 10, true);
        jdbc.sql("""
                create function fail_on_order_line() returns trigger as $$
                begin
                    raise exception 'simulated crash while saving';
                end
                $$ language plpgsql""").update();
        jdbc.sql("create trigger fail_on_order_line before insert on order_line for each row execute function fail_on_order_line()").update();
        try {
            assertThatThrownBy(() -> orderService.placeOrder(command(meal, 1))).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.sql("drop trigger fail_on_order_line on order_line").update();
            jdbc.sql("drop function fail_on_order_line()").update();
        }

        // the order never existed, so the kitchen must never hear about it: no phantom tickets
        assertThat(count("orders")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    @Test
    void anEventThatCanNeverBeDeliveredIsParkedAndDoesNotBlockTheOthers() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        // an old event of a kind nobody understands: it sits at the front of the queue
        jdbc.sql("""
                insert into outbox_event (id, aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at)
                values (gen_random_uuid(), gen_random_uuid(), 'Mystery', '{}'::jsonb, 'PENDING', 0, :old, :old)""")
                .param("old", OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC))
                .update();
        place(meal, 1);

        dispatcher.dispatchBatch();

        assertThat(count("kitchen_ticket")).isEqualTo(1);   // the real order got through anyway
        assertThat(jdbc.sql("select attempts from outbox_event where event_type = 'Mystery'").query(Integer.class).single()).isEqualTo(1);

        // the test profile allows 3 attempts: try twice more and the event is parked
        makeEverythingDue();
        dispatcher.dispatchBatch();
        makeEverythingDue();
        dispatcher.dispatchBatch();

        assertThat(jdbc.sql("select status from outbox_event where event_type = 'Mystery'").query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("select last_error from outbox_event where event_type = 'Mystery'").query(String.class).single())
                .contains("Unknown event type");
        makeEverythingDue();
        assertThat(dispatcher.dispatchBatch()).isZero();    // a parked event is not tried again
    }

    @Test
    void aDispatcherSkipsAnEventThatAnotherDispatcherIsHandlingInsteadOfWaiting() throws Exception {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        place(meal, 1);
        place(meal, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // another dispatcher is in the middle of the oldest event: it holds that row's lock
        Thread busyDispatcher = new Thread(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("select id from outbox_event order by created_at limit 1 for update").query().listOfRows();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        busyDispatcher.start();
        locked.await();

        int handled;
        try {
            handled = dispatcher.dispatchBatch();   // must not wait for the locked event
        } finally {
            release.countDown();
            busyDispatcher.join();
        }

        assertThat(handled).isEqualTo(1);                       // it took the other event
        assertThat(count("kitchen_ticket")).isEqualTo(1);
        assertThat(outboxStatuses()).containsExactlyInAnyOrder("PENDING", "PUBLISHED");

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);    // once the lock is gone, the first event is delivered too
        assertThat(count("kitchen_ticket")).isEqualTo(2);
    }

    // ---- helpers ----

    private ResponseEntity<JsonNode> place(UUID product, int quantity) {
        String json = "{\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}],"
                + "\"payment\":{\"method\":\"CASH\",\"amount\":\"100000\",\"currency\":\"INR\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity("/api/v1/orders", new HttpEntity<>(json, headers), JsonNode.class);
    }

    private PlaceOrderCommand command(UUID product, int quantity) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new PlaceOrderCommand.Item(product, new Quantity(quantity))),
                new Payment(PaymentMethod.CASH, Money.of("100000", "INR")));
    }

    private List<String> outboxStatuses() {
        return jdbc.sql("select status from outbox_event order by created_at").query(String.class).list();
    }

    private List<Integer> attemptsOfEveryEvent() {
        return jdbc.sql("select attempts from outbox_event order by created_at").query(Integer.class).list();
    }

    /** Skip the waiting time between attempts, so a test does not have to wait for the back-off to pass. */
    private void makeEverythingDue() {
        jdbc.sql("update outbox_event set next_attempt_at = :due where status = 'PENDING'").param("due", longAgo()).update();
    }

    // The application's own clock, not the database's now(): the Docker VM's clock can run a little ahead of ours,
    // and the dispatcher compares against the application's clock.
    private OffsetDateTime longAgo() {
        return OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC);
    }

    /** Makes every kitchen ticket insert fail, like a kitchen system that is down. */
    private void breakTheKitchen() {
        jdbc.sql("""
                create function fail_kitchen() returns trigger as $$
                begin
                    raise exception 'the kitchen display is down';
                end
                $$ language plpgsql""").update();
        jdbc.sql("create trigger fail_kitchen before insert on kitchen_ticket for each row execute function fail_kitchen()").update();
    }

    private void repairTheKitchen() {
        jdbc.sql("drop trigger fail_kitchen on kitchen_ticket").update();
        jdbc.sql("drop function fail_kitchen()").update();
    }
}
