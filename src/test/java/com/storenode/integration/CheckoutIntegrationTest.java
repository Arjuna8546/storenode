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
        PlaceOrderCommand command = new PlaceOrderCommand("KIOSK-07",
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

    @Test
    void anUnknownOrderIsNotFound() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/orders/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ORDER_NOT_FOUND");
    }

    // ---- helpers ----

    private ResponseEntity<JsonNode> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Terminal-Id", "KIOSK-07");
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
