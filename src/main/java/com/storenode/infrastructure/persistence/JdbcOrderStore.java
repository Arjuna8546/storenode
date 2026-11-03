package com.storenode.infrastructure.persistence;

import com.storenode.application.DuplicateRequestException;
import com.storenode.application.port.OrderStore;
import com.storenode.domain.order.*;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.storenode.domain.shared.TaxRate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOrderStore implements OrderStore {

    private final JdbcClient jdbc;

    public JdbcOrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Order order, String idempotencyKey) {
        try {
            insertOrder(order, idempotencyKey);
        } catch (DuplicateKeyException e) {
            if (isIdempotencyKeyClash(e)) {
                throw new DuplicateRequestException(idempotencyKey);
            }
            throw e;   // any other unique clash (the order number, say) is a real bug and must stay loud
        }
    }

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("select id from orders where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional()
                .flatMap(this::findById);
    }

    // Postgres names the broken constraint in its message:
    // duplicate key value violates unique constraint "uq_orders_idempotency_key"
    private static boolean isIdempotencyKeyClash(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_orders_idempotency_key");
    }

    private void insertOrder(Order order, String idempotencyKey) {
        OffsetDateTime placedAt = OffsetDateTime.ofInstant(order.placedAt(), ZoneOffset.UTC);
        jdbc.sql("""
                        insert into orders (id, branch_id, order_number, business_date, terminal_id, status, currency,
                                            subtotal, tax_total, total, payment_method, amount_tendered, change_due,
                                            placed_at, created_at, updated_at, idempotency_key)
                        values (:id, :branchId, :number, :businessDate, :terminalId, :status, :currency,
                                :subtotal, :taxTotal, :total, :method, :tendered, :changeDue,
                                :placedAt, :placedAt, :placedAt, :idempotencyKey)""")
                .param("id", order.id())
                .param("branchId", order.branchId())
                .param("number", order.number().format())
                .param("businessDate", order.businessDate())
                .param("terminalId", order.terminalId())
                .param("status", order.status().name())
                .param("currency", order.total().currency().getCurrencyCode())
                .param("subtotal", order.subtotal().amount())
                .param("taxTotal", order.taxTotal().amount())
                .param("total", order.total().amount())
                .param("method", order.payment().method().name())
                .param("tendered", order.payment().tendered().amount())
                .param("changeDue", order.changeDue().amount())
                .param("placedAt", placedAt)
                .param("idempotencyKey", idempotencyKey)
                .update();

        for (OrderLine line : order.lines()) {
            jdbc.sql("""
                            insert into order_line (id, order_id, line_no, product_id, sku, name, unit_price, quantity,
                                                    tax_rate, net_amount, tax_amount, line_total, requires_preparation)
                            values (:id, :orderId, :lineNo, :productId, :sku, :name, :unitPrice, :quantity,
                                    :taxRate, :net, :tax, :lineTotal, :prep)""")
                    .param("id", line.id())
                    .param("orderId", order.id())
                    .param("lineNo", line.lineNo())
                    .param("productId", line.productId())
                    .param("sku", line.sku())
                    .param("name", line.name())
                    .param("unitPrice", line.unitPrice().amount())
                    .param("quantity", line.quantity().value())
                    .param("taxRate", line.taxRate().value())
                    .param("net", line.netAmount().amount())
                    .param("tax", line.taxAmount().amount())
                    .param("lineTotal", line.lineTotal().amount())
                    .param("prep", line.requiresPreparation())
                    .update();
        }
    }

    @Override
    public Optional<Order> findById(UUID id) {
        Optional<OrderRow> row = jdbc.sql("""
                        select id, branch_id, order_number, terminal_id, status, currency, subtotal, tax_total, total,
                               payment_method, amount_tendered, change_due, placed_at
                        from orders where id = :id""")
                .param("id", id)
                .query(JdbcOrderStore::toRow)
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }

        OrderRow header = row.get();
        Currency currency = header.currency;
        List<OrderLine> lines = jdbc.sql("""
                        select id, line_no, product_id, sku, name, unit_price, quantity, tax_rate, net_amount,
                               tax_amount, line_total, requires_preparation
                        from order_line where order_id = :id order by line_no""")
                .param("id", id)
                .query((rs, rowNum) -> toLine(rs, currency))
                .list();

        Payment payment = new Payment(header.method, money(header.tendered, currency));
        return Optional.of(new Order(header.id, header.branchId, header.number, header.terminalId, header.status,
                lines, money(header.subtotal, currency), money(header.taxTotal, currency),
                money(header.total, currency), payment, money(header.changeDue, currency), header.placedAt));
    }

    private record OrderRow(UUID id, UUID branchId, OrderNumber number, String terminalId, OrderStatus status,
                            Currency currency, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
                            PaymentMethod method, BigDecimal tendered, BigDecimal changeDue, Instant placedAt) {
    }

    private static OrderRow toRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                OrderNumber.parse(rs.getString("order_number")), rs.getString("terminal_id"),
                OrderStatus.valueOf(rs.getString("status")), Currency.getInstance(rs.getString("currency")),
                rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_total"), rs.getBigDecimal("total"),
                PaymentMethod.valueOf(rs.getString("payment_method")), rs.getBigDecimal("amount_tendered"),
                rs.getBigDecimal("change_due"), rs.getObject("placed_at", OffsetDateTime.class).toInstant());
    }

    private static OrderLine toLine(ResultSet rs, Currency currency) throws SQLException {
        return new OrderLine(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                money(rs.getBigDecimal("unit_price"), currency), new Quantity(rs.getInt("quantity")),
                new TaxRate(rs.getBigDecimal("tax_rate")), money(rs.getBigDecimal("net_amount"), currency),
                money(rs.getBigDecimal("tax_amount"), currency), money(rs.getBigDecimal("line_total"), currency),
                rs.getBoolean("requires_preparation"));
    }

    private static Money money(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }
}

