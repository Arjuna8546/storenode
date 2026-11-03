package com.storenode.infrastructure.persistence;

import com.storenode.application.port.InventoryStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcInventoryStore implements InventoryStore {

    private final JdbcClient jdbc;

    public JdbcInventoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryTake(UUID productId, int quantity) {
        int rows = jdbc.sql("""
                        update inventory
                        set quantity = quantity - :n, version = version + 1, updated_at = now()
                        where product_id = :id and quantity >= :n""")
                .param("n", quantity)
                .param("id", productId)
                .update();
        return rows == 1;
    }
}

