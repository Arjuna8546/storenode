package com.storenode.application.port;

import com.storenode.domain.event.OrderPlaced;

/** Turns an event into the text we store (and back). The application does not need to know it is JSON. */
public interface EventCodec {

    String encode(OrderPlaced event);

    OrderPlaced decode(String eventType, String payload);
}
