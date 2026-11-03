package com.storenode.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

/**
 * Base class for tests that need the whole application and a real Postgres.
 * The container is started once and shared by every subclass (Spring caches the application context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    // same id as storenode.branch.id in application.yaml
    private static final UUID BRANCH_ID = UUID.fromString("0190f3c0-0000-7000-8000-000000000001");

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void emptyTheTables() {
        jdbc.sql("truncate table kitchen_ticket, outbox_event, order_line, orders, inventory, product, fiscal_counter").update();

    }

    /** Puts a product and its stock into the database and returns the product id. */
    protected UUID addProduct(String sku, String price, int stock) {
        return addProduct(sku, price, stock, false);
    }

    /** Same, for a product the kitchen has to prepare (a meal) when requiresPreparation is true. */
    protected UUID addProduct(String sku, String price, int stock, boolean requiresPreparation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into product (id, branch_id, sku, name, unit_price, currency, tax_rate,
                                             requires_preparation, created_at, updated_at)
                        values (:id, :branch, :sku, :sku, :price::numeric, 'INR', 0.05, :prep, now(), now())""")
                .param("id", id)
                .param("branch", BRANCH_ID)
                .param("sku", sku)
                .param("price", price)
                .param("prep", requiresPreparation)
                .update();
        jdbc.sql("insert into inventory (product_id, branch_id, quantity, updated_at) values (:id, :branch, :stock, now())")
                .param("id", id)
                .param("branch", BRANCH_ID)
                .param("stock", stock)
                .update();
        return id;
    }

    protected int stockOf(UUID productId) {
        return jdbc.sql("select quantity from inventory where product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    protected long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
