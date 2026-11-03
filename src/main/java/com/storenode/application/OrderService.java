package com.storenode.application;

import com.storenode.application.port.*;
import com.storenode.domain.catalog.Product;
import com.storenode.domain.exception.OrderNotFoundException;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.event.OrderPlaced;
import com.storenode.domain.exception.ProductNotFoundException;
import com.storenode.domain.order.*;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Quantity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class OrderService {

    public record PlaceOrderCommand(String idempotencyKey, String terminalId, List<Item> items, Payment payment) {

        public PlaceOrderCommand {
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
                throw new IllegalArgumentException("The idempotency key must be 1 to 100 characters");
            }
        }

        public record Item(UUID productId, Quantity quantity) {
        }
    }

    private final TransactionRunner transactions;
    private final ProductCatalog catalog;
    private final InventoryStore inventory;
    private final FiscalSequence fiscalSequence;
    private final OrderStore orders;
    private final OutboxStore outbox;
    private final BranchSettings branch;
    private final Clock clock;

    public OrderService(TransactionRunner transactions, ProductCatalog catalog, InventoryStore inventory,
                        FiscalSequence fiscalSequence, OrderStore orders, OutboxStore outbox, BranchSettings branch,
                        Clock clock) {
        this.transactions = transactions;
        this.catalog = catalog;
        this.inventory = inventory;
        this.fiscalSequence = fiscalSequence;
        this.orders = orders;
        this.outbox = outbox;
        this.branch = branch;
        this.clock = clock;
    }

    public Order placeOrder(PlaceOrderCommand command) {
        // A retry of a sale we already finished: hand back the same order and touch nothing.
        Optional<Order> alreadyPlaced = orders.findByIdempotencyKey(command.idempotencyKey());
        if (alreadyPlaced.isPresent()) {
            return alreadyPlaced.get();
        }

        try {
            return placeNewOrder(command);
        } catch (DuplicateRequestException e) {
            // Two requests with the same key ran at the same moment and the other one saved first.
            // Our attempt was rolled back completely (stock, number, everything), so return the winner.
            return orders.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
        }
    }

    private Order placeNewOrder(PlaceOrderCommand command) {
        return transactions.inTransaction(() -> {
            OrderDraft draft = OrderDraft.create(requestedLines(command), command.payment());

            // Always lock the stock rows in the same order (by product id), whatever order the customer
            // listed them in. Two orders can then never wait for each other: no deadlock.
            List<OrderLine> inLockOrder = draft.lines().stream()
                    .sorted(Comparator.comparing(OrderLine::productId))
                    .toList();
            for (OrderLine line : inLockOrder) {
                takeStock(line);
            }

            // The counter is kept per business day, so the day has to be known before asking for a number.
            LocalDate businessDate = LocalDate.ofInstant(clock.instant(), branch.zoneId());
            int sequence = fiscalSequence.next(branch.code(), businessDate);
            OrderNumber number = new OrderNumber(branch.code(), businessDate, sequence);

            // The time is read only now, while we hold the counter's lock. Numbers are handed out one at a time,
            // so a later number can never get an earlier time. (Postgres keeps microseconds, so cut the time there;
            // then what we return equals what we store.)
            Instant placedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

            Order order = draft.place(Ids.newId(), branch.id(), number, command.terminalId(), placedAt);
            orders.save(order, command.idempotencyKey());

            // Written in this same transaction: either the order and its kitchen event are both saved, or neither is.
            // The kitchen itself is never called from here, so it can be slow or down without touching checkout.
            OrderPlaced.from(order).ifPresent(event -> outbox.append(event, placedAt));
            return order;
        });
    }

    public Order getOrder(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private List<OrderDraft.RequestedLine> requestedLines(PlaceOrderCommand command) {
        List<UUID> ids = command.items().stream().map(PlaceOrderCommand.Item::productId).toList();
        Map<UUID, Product> products = catalog.findByIds(ids);

        List<OrderDraft.RequestedLine> lines = new ArrayList<>();
        for (PlaceOrderCommand.Item item : command.items()) {
            Product product = products.get(item.productId());
            if (product == null) {
                throw new ProductNotFoundException(item.productId());
            }
            lines.add(new OrderDraft.RequestedLine(product, item.quantity()));
        }
        return lines;
    }

    // The shelf check and the change are one database step (see InventoryStore.tryTake).
    private void takeStock(OrderLine line) {
        int wanted = line.quantity().value();
        if (!inventory.tryTake(line.productId(), wanted)) {
            throw new OutOfStockException(line.productId(), line.sku(), wanted);
        }
    }
}

