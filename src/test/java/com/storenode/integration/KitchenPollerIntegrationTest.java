package com.storenode.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The one test where the poller is switched on: nobody calls the dispatcher, the schedule does. */
@TestPropertySource(properties = {"storenode.outbox.enabled=true", "storenode.outbox.poll-interval-ms=200"})
class KitchenPollerIntegrationTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void anOrderReachesTheKitchenByItselfWithinMoments() {
        UUID meal = addProduct("MEAL", "100.00", 10, true);
        String json = "{\"lines\":[{\"productId\":\"" + meal + "\",\"quantity\":1}],"
                + "\"payment\":{\"method\":\"CASH\",\"amount\":\"1000\",\"currency\":\"INR\"}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> placed = http.postForEntity("/api/v1/orders", new HttpEntity<>(json, headers), JsonNode.class);

        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(count("kitchen_ticket")).isEqualTo(1));
    }
}
