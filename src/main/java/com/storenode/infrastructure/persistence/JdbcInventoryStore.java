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
    public int available(UUID productId) {
        return jdbc.sql("select quantity from inventory where product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    @Override
    public void setQuantity(UUID productId, int quantity) {
        jdbc.sql("update inventory set quantity = :quantity, version = version + 1, updated_at = now() where product_id = :id")
                .param("quantity", quantity)
                .param("id", productId)
                .update();
    }
}

