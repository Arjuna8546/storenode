package com.storenode.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storenode.application.port.EventCodec;
import com.storenode.domain.event.OrderPlaced;
import org.springframework.stereotype.Component;

@Component
public class JacksonEventCodec implements EventCodec {

    private final ObjectMapper mapper;

    public JacksonEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String encode(OrderPlaced event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the event as JSON", e);
        }
    }

    @Override
    public OrderPlaced decode(String eventType, String payload) {
        if (!OrderPlaced.TYPE.equals(eventType)) {
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
        try {
            return mapper.readValue(payload, OrderPlaced.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not read the event: " + e.getOriginalMessage(), e);
        }
    }
}
