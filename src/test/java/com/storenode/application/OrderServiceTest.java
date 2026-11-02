package com.storenode.application;

import com.storenode.application.OrderService.PlaceOrderCommand;
import com.storenode.application.port.FiscalSequence;
import com.storenode.application.port.InventoryStore;
import com.storenode.application.port.OrderStore;
import com.storenode.application.port.ProductCatalog;
import com.storenode.application.port.TransactionRunner;
import com.storenode.domain.catalog.Product;
import com.storenode.domain.exception.OutOfStockException;
import com.storenode.domain.exception.PaymentRejectedException;
import com.storenode.domain.exception.ProductNotFoundException;
import com.storenode.domain.order.Order;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.storenode.domain.shared.TaxRate;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private final Product wrap = new Product(Ids.newId(), "WRAP", "Wrap", Money.of("100.00", "INR"), TaxRate.of("0.05"), true);

    private final FakeCatalog catalog = new FakeCatalog(wrap);
    private final FakeInventory inventory = new FakeInventory();
    private final FakeOrders orders = new FakeOrders();
    private final FakeSequence sequence = new FakeSequence();

    // 20:00 UTC on 1 November is already 01:30 on 2 November in Kolkata
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC);
    private final BranchSettings branch = new BranchSettings(Ids.newId(), "AIR01", ZoneId.of("Asia/Kolkata"));

    private final OrderService service = new OrderService(new DirectTransactions(), catalog, inventory, sequence, orders, branch, clock);

    @Test
    void placesTheOrderAndTakesTheStock() {
        inventory.stock.put(wrap.id(), 10);

        Order order = service.placeOrder(command(3, "500.00"));

        assertThat(inventory.stock.get(wrap.id())).isEqualTo(7);
        assertThat(order.total()).isEqualTo(Money.of("315.00", "INR"));
        assertThat(order.changeDue()).isEqualTo(Money.of("185.00", "INR"));
        assertThat(orders.saved).containsExactly(order);
        assertThat(service.getOrder(order.id())).isEqualTo(order);
    }

    @Test
    void theOrderNumberUsesTheBranchsLocalDate() {
        inventory.stock.put(wrap.id(), 10);

        Order order = service.placeOrder(command(1, "500.00"));

        assertThat(order.businessDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(order.number().format()).isEqualTo("ORD-AIR01-20261102-0001");
    }

    @Test
    void refusesMoreThanTheStockAndChangesNothing() {
        inventory.stock.put(wrap.id(), 2);

        assertThatThrownBy(() -> service.placeOrder(command(3, "500.00"))).isInstanceOf(OutOfStockException.class);

        assertThat(inventory.stock.get(wrap.id())).isEqualTo(2);
        assertThat(orders.saved).isEmpty();
    }

    @Test
    void refusesAnUnknownProduct() {
        OrderService.PlaceOrderCommand command = new PlaceOrderCommand("KIOSK-07",
                List.of(new PlaceOrderCommand.Item(Ids.newId(), new Quantity(1))),
                new Payment(PaymentMethod.CASH, Money.of("500.00", "INR")));

        assertThatThrownBy(() -> service.placeOrder(command)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void aBadPaymentIsRejectedBeforeStockOrNumbersAreTouched() {
        inventory.stock.put(wrap.id(), 10);

        assertThatThrownBy(() -> service.placeOrder(command(3, "100.00"))).isInstanceOf(PaymentRejectedException.class);

        assertThat(inventory.stock.get(wrap.id())).isEqualTo(10);
        assertThat(sequence.issued).isZero();
        assertThat(orders.saved).isEmpty();
    }

    private PlaceOrderCommand command(int quantity, String cash) {
        return new PlaceOrderCommand("KIOSK-07",
                List.of(new PlaceOrderCommand.Item(wrap.id(), new Quantity(quantity))),
                new Payment(PaymentMethod.CASH, Money.of(cash, "INR")));
    }

    // ---- fakes: the same ports, held in memory ----

    private static class DirectTransactions implements TransactionRunner {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }

    private static class FakeCatalog implements ProductCatalog {
        private final Map<UUID, Product> products = new HashMap<>();

        FakeCatalog(Product... all) {
            for (Product product : all) {
                products.put(product.id(), product);
            }
        }

        @Override
        public List<ProductAvailability> findAllWithAvailability() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, Product> findByIds(Collection<UUID> productIds) {
            Map<UUID, Product> found = new HashMap<>();
            for (UUID id : productIds) {
                if (products.containsKey(id)) {
                    found.put(id, products.get(id));
                }
            }
            return found;
        }
    }

    private static class FakeInventory implements InventoryStore {
        final Map<UUID, Integer> stock = new HashMap<>();

        @Override
        public int available(UUID productId) {
            return stock.getOrDefault(productId, 0);
        }

        @Override
        public void setQuantity(UUID productId, int quantity) {
            stock.put(productId, quantity);
        }
    }

    private static class FakeOrders implements OrderStore {
        final List<Order> saved = new ArrayList<>();

        @Override
        public void save(Order order) {
            saved.add(order);
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return saved.stream().filter(order -> order.id().equals(id)).findFirst();
        }
    }

    private static class FakeSequence implements FiscalSequence {
        int issued = 0;

        @Override
        public int next(String branchCode, LocalDate businessDate) {
            return ++issued;
        }
    }
}
