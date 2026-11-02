package com.storenode.application;

import com.storenode.application.port.*;
import com.storenode.domain.catalog.Product;
import com.storenode.domain.exception.OrderNotFoundException;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.exception.ProductNotFoundException;
import com.storenode.domain.order.*;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Quantity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OrderService {

    public record PlaceOrderCommand(String terminalId, List<Item> items, Payment payment) {

        public record Item(UUID productId, Quantity quantity) {
        }
    }

    private final TransactionRunner transactions;
    private final ProductCatalog catalog;
    private final InventoryStore inventory;
    private final FiscalSequence fiscalSequence;
    private final OrderStore orders;
    private final BranchSettings branch;
    private final Clock clock;

    public OrderService(TransactionRunner transactions, ProductCatalog catalog, InventoryStore inventory,
                        FiscalSequence fiscalSequence, OrderStore orders, BranchSettings branch, Clock clock) {
        this.transactions = transactions;
        this.catalog = catalog;
        this.inventory = inventory;
        this.fiscalSequence = fiscalSequence;
        this.orders = orders;
        this.branch = branch;
        this.clock = clock;
    }

    public Order placeOrder(PlaceOrderCommand command) {
        return transactions.inTransaction(() -> {
            OrderDraft draft = OrderDraft.create(requestedLines(command), command.payment());

            for (OrderLine line : draft.lines()) {
                takeStock(line);
            }

            // Postgres keeps microseconds, so cut the time there; then what we return equals what we store
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate businessDate = LocalDate.ofInstant(now, branch.zoneId());
            int sequence = fiscalSequence.next(branch.code(), businessDate);
            OrderNumber number = new OrderNumber(branch.code(), businessDate, sequence);

            Order order = draft.place(Ids.newId(), branch.id(), number, command.terminalId(), now);
            orders.save(order);
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

    // Simple on purpose: read the stock, check it here, write the new number back.
    // Phase 6 shows why this is not safe when many terminals do it at once.
    private void takeStock(OrderLine line) {
        int available = inventory.available(line.productId());
        int wanted = line.quantity().value();
        if (available < wanted) {
            throw new OutOfStockException(line.productId(), line.sku(), wanted);
        }
        inventory.setQuantity(line.productId(), available - wanted);
    }
}

