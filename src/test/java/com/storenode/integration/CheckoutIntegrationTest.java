package com.storenode.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.storenode.application.OrderService;
import com.storenode.application.OrderService.PlaceOrderCommand;
import com.storenode.application.port.OrderStore;
import com.storenode.domain.order.Order;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIntegrationTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStore orderStore;

    @Test
    void placesAnOrderAndTakesTheStock() {
        UUID wrap = addProduct("WRAP", "100.00", 10);

        ResponseEntity<JsonNode> response = post(order("500", line(wrap, 3)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("total").asText()).isEqualTo("315.00");
        assertThat(response.getBody().get("payment").get("changeDue").asText()).isEqualTo("185.00");
        assertThat(stockOf(wrap)).isEqualTo(7);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_line")).isEqualTo(1);
    }

    @Test
    void anOrderCanBeReadBackFromItsLocation() {
        UUID wrap = addProduct("WRAP", "100.00", 10);

        ResponseEntity<JsonNode> created = post(order("500", line(wrap, 1)));
        ResponseEntity<JsonNode> fetched = http.getForEntity(created.getHeaders().getLocation(), JsonNode.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    @Test
    void whatWeSaveIsWhatWeReadBack() {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        PlaceOrderCommand command = new PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new PlaceOrderCommand.Item(wrap, new Quantity(2))),
                new Payment(PaymentMethod.CASH, Money.of("500.00", "INR")));

        Order placed = orderService.placeOrder(command);

        assertThat(orderStore.findById(placed.id())).contains(placed);
    }

    @Test
    void numbersCountUpThroughTheDay() {
        UUID wrap = addProduct("WRAP", "100.00", 10);

        String first = post(order("500", line(wrap, 1))).getBody().get("orderNumber").asText();
        String second = post(order("500", line(wrap, 1))).getBody().get("orderNumber").asText();

        assertThat(first).startsWith("ORD-AIR01-").endsWith("-0001");
        assertThat(second).startsWith("ORD-AIR01-").endsWith("-0002");
    }

    @Test
    void anOrderThatRunsOutOfStockHalfwayLeavesEverythingUntouched() {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        UUID latte = addProduct("LATTE", "80.00", 1);

        // the wrap line is fine and takes its stock first, then the latte line fails
        ResponseEntity<JsonNode> response = post(order("1000", line(wrap, 2), line(latte, 5)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(stockOf(wrap)).isEqualTo(10);   // rolled back
        assertThat(stockOf(latte)).isEqualTo(1);
        assertThat(count("orders")).isZero();
    }

    // ---- Scenario C ----

    @Test
    void sendingTheSameOrderTwiceGivesBackTheSameOrderAndTakesTheStockOnce() {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        String body = order("500", line(wrap, 3));

        ResponseEntity<JsonNode> first = post(body, "sale-1");
        ResponseEntity<JsonNode> retry = post(body, "sale-1");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());   // the very same order, same number
        assertThat(stockOf(wrap)).isEqualTo(7);                    // taken once, not twice
        assertThat(count("orders")).isEqualTo(1);
        assertThat(lastIssuedNumber()).isEqualTo(1);               // no number wasted
    }

    @Test
    void aFailedAttemptDoesNotUseUpTheKey() {
        UUID wrap = addProduct("WRAP", "100.00", 2);
        String body = order("500", line(wrap, 3));

        ResponseEntity<JsonNode> refused = post(body, "sale-2");
        jdbc.sql("update inventory set quantity = 10 where product_id = :id").param("id", wrap).update();
        ResponseEntity<JsonNode> retry = post(body, "sale-2");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);   // the key was free, so this is a new attempt
        assertThat(stockOf(wrap)).isEqualTo(7);
    }

    @Test
    void anOrderWithoutAnIdempotencyKeyIsRefused() {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/orders",
                new HttpEntity<>(order("500", line(wrap, 1)), headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MISSING_HEADER");
        assertThat(stockOf(wrap)).isEqualTo(10);
    }

    @Test
    void anUnknownOrderIsNotFound() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/orders/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ORDER_NOT_FOUND");
    }

    // ---- helpers ----

    private int lastIssuedNumber() {
        return jdbc.sql("select max(last_number) from fiscal_counter").query(Integer.class).single();
    }

    private ResponseEntity<JsonNode> post(String json) {
        return post(json, UUID.randomUUID().toString());   // every sale gets its own key
    }

    private ResponseEntity<JsonNode> post(String json, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Terminal-Id", "KIOSK-07");
        headers.add("Idempotency-Key", idempotencyKey);
        return http.postForEntity("/api/v1/orders", new HttpEntity<>(json, headers), JsonNode.class);
    }

    private String line(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private String order(String cash, String... lines) {
        return "{\"lines\":[" + String.join(",", lines) + "],"
                + "\"payment\":{\"method\":\"CASH\",\"amount\":\"" + cash + "\",\"currency\":\"INR\"}}";
    }
}
