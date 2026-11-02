package com.storenode.application.port;

import java.util.UUID;

public interface InventoryStore {

    int available(UUID productId);

    void setQuantity(UUID productId, int quantity);
}