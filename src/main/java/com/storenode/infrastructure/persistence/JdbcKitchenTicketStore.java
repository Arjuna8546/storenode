package com.storenode.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storenode.application.port.KitchenTicketStore;
import com.storenode.domain.kitchen.KitchenTicket;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcKitchenTicketStore implements KitchenTicketStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcKitchenTicketStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // "on conflict (order_id) do nothing": the second delivery of the same event changes nothing.
    @Override
    public void createIfAbsent(KitchenTicket ticket) {
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(ticket.createdAt(), ZoneOffset.UTC);
        jdbc.sql("""
                        insert into kitchen_ticket (id, branch_id, order_id, order_number, items, status, created_at, updated_at)
                        values (:id, :branchId, :orderId, :orderNumber, cast(:items as jsonb), :status, :createdAt, :createdAt)
                        on conflict (order_id) do nothing""")
                .param("id", ticket.id())
                .param("branchId", ticket.branchId())
                .param("orderId", ticket.orderId())
                .param("orderNumber", ticket.orderNumber())
                .param("items", write(ticket.items()))
                .param("status", ticket.status().name())
                .param("createdAt", createdAt)
                .update();
    }

    @Override
    public List<KitchenTicket> findOpen() {
        return jdbc.sql("""
                        select id, branch_id, order_id, order_number, items::text as items, status, created_at
                        from kitchen_ticket where status = 'NEW' order by created_at""")
                .query(this::toTicket)
                .list();
    }

    private KitchenTicket toTicket(ResultSet rs, int rowNum) throws SQLException {
        return new KitchenTicket(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("order_number"), read(rs.getString("items")),
                KitchenTicket.Status.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private String write(List<KitchenTicket.Item> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the ticket items as JSON", e);
        }
    }

    private List<KitchenTicket.Item> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the ticket items", e);
        }
    }
}
