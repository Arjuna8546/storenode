package com.storenode.integration;

import com.storenode.application.OrderService;
import com.storenode.application.OrderService.PlaceOrderCommand;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the database that the migrations really built, not the Java code.
 * The rules here are what lets thousands of branches send their data to Central HQ later without clashing.
 */
class SchemaIntegrationTest extends IntegrationTest {

    /** Business data that Central HQ will receive: one UUID key, the branch it came from, a version and a timestamp. */
    private static final Set<String> SYNCED = Set.of("product", "inventory", "orders", "kitchen_ticket");

    /** Rows that always travel together with their parent, so they only need a UUID key and the link to it. */
    private static final Set<String> CHILD_ROWS = Set.of("order_line");

    /** Working state of this one node. It never leaves the branch, so it needs no sync columns. */
    private static final Set<String> LOCAL_ONLY = Set.of("fiscal_counter", "outbox_event", "flyway_schema_history");

    @Autowired
    private OrderService orderService;

    @Test
    void everyTableHasBeenDecidedOn() {
        Set<String> expected = new TreeSet<>();
        expected.addAll(SYNCED);
        expected.addAll(CHILD_ROWS);
        expected.addAll(LOCAL_ONLY);

        // If this fails you added a table: decide whether it is synced, a child row, or local, and list it above.
        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void syncedTablesAreKeyedByAUuidAndCarryTheSyncColumns() {
        for (String table : SYNCED) {
            assertThat(primaryKeyTypes(table)).as("primary key of " + table).containsExactly("uuid");
            assertThat(columnType(table, "branch_id")).as(table + ".branch_id").isEqualTo("uuid");
            assertThat(columnType(table, "version")).as(table + ".version").isEqualTo("bigint");
            assertThat(columnType(table, "updated_at")).as(table + ".updated_at").isEqualTo("timestamp with time zone");
        }
    }

    @Test
    void childRowsHaveTheirOwnUuidKeyAndPointAtTheirParent() {
        for (String table : CHILD_ROWS) {
            assertThat(primaryKeyTypes(table)).as("primary key of " + table).containsExactly("uuid");
        }
        assertThat(columnType("order_line", "order_id")).isEqualTo("uuid");
        assertThat(foreignKeyTargets("order_line")).contains("orders");
    }

    @Test
    void nothingIsNumberedByTheDatabaseItself() {
        // serial / identity / sequence columns count 1, 2, 3 on every node, so two nodes would reuse the same keys
        List<String> autoNumbered = jdbc.sql("""
                        select table_name || '.' || column_name
                        from information_schema.columns
                        where table_schema = 'public'
                          and (column_default like 'nextval%' or is_identity = 'YES')""")
                .query(String.class).list();

        assertThat(autoNumbered).isEmpty();
    }

    @Test
    void everyMigrationRanSuccessfully() {
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success = false").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success = true").query(Long.class).single()).isPositive();
    }

    @Test
    void aSaleBumpsTheInventoryVersionSoSyncCanSeeTheChange() {
        UUID wrap = addProduct("WRAP", "100.00", 10);
        long versionBefore = inventoryVersion(wrap);
        OffsetDateTime changedBefore = inventoryUpdatedAt(wrap);

        orderService.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new PlaceOrderCommand.Item(wrap, new Quantity(1))),
                new Payment(PaymentMethod.CASH, Money.of("500.00", "INR"))));

        assertThat(inventoryVersion(wrap)).isEqualTo(versionBefore + 1);
        assertThat(inventoryUpdatedAt(wrap)).isAfter(changedBefore);
    }

    // ---- looking at the database's own catalogue ----

    private List<String> tableNames() {
        return jdbc.sql("""
                        select table_name from information_schema.tables
                        where table_schema = 'public' and table_type = 'BASE TABLE'""")
                .query(String.class).list();
    }

    private String columnType(String table, String column) {
        return jdbc.sql("""
                        select data_type from information_schema.columns
                        where table_schema = 'public' and table_name = :table and column_name = :column""")
                .param("table", table)
                .param("column", column)
                .query(String.class)
                .optional()
                .orElse("(missing)");
    }

    private List<String> primaryKeyTypes(String table) {
        return jdbc.sql("""
                        select c.data_type
                        from information_schema.table_constraints tc
                        join information_schema.key_column_usage k
                          on k.constraint_name = tc.constraint_name and k.table_schema = tc.table_schema
                        join information_schema.columns c
                          on c.table_schema = k.table_schema and c.table_name = k.table_name and c.column_name = k.column_name
                        where tc.constraint_type = 'PRIMARY KEY' and tc.table_schema = 'public' and tc.table_name = :table
                        order by k.ordinal_position""")
                .param("table", table)
                .query(String.class).list();
    }

    private List<String> foreignKeyTargets(String table) {
        return jdbc.sql("""
                        select distinct ccu.table_name
                        from information_schema.table_constraints tc
                        join information_schema.constraint_column_usage ccu
                          on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
                        where tc.constraint_type = 'FOREIGN KEY' and tc.table_schema = 'public' and tc.table_name = :table""")
                .param("table", table)
                .query(String.class).list();
    }

    private long inventoryVersion(UUID productId) {
        return jdbc.sql("select version from inventory where product_id = :id").param("id", productId).query(Long.class).single();
    }

    private OffsetDateTime inventoryUpdatedAt(UUID productId) {
        return jdbc.sql("select updated_at from inventory where product_id = :id").param("id", productId)
                .query(OffsetDateTime.class).single();
    }
}
