package com.storenode.application.port;

import java.util.UUID;

public interface InventoryStore {

    boolean tryTake(UUID productId, int quantity);
}