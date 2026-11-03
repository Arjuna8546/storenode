package com.storenode.application;

import com.storenode.application.OrderService.PlaceOrderCommand;
import com.storenode.application.port.FiscalSequence;
import com.storenode.application.port.InventoryStore;
import com.storenode.application.port.OrderStore;
import com.storenode.application.port.OutboxStore;
import com.storenode.application.port.ProductCatalog;
import com.storenode.application.port.TransactionRunner;
import com.storenode.domain.catalog.Product;
import com.storenode.domain.event.OrderPlaced;
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
    private final FakeOutbox outbox = new FakeOutbox();

    // 20:00 UTC on 1 November is already 01:30 on 2 November in Kolkata
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC);
    private final BranchSettings branch = new BranchSettings(Ids.newId(), "AIR01", ZoneId.of("Asia/Kolkata"));

    private final OrderService service = new OrderService(new DirectTransactions(), catalog, inventory, sequence, orders, outbox, branch, clock);

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
        OrderService.PlaceOrderCommand command = new PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
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

    @Test
    void sendingTheSameKeyAgainReturnsTheFirstOrderAndTouchesNothing() {
        inventory.stock.put(wrap.id(), 10);

        Order first = service.placeOrder(command("sale-1", 3, "500.00"));
        Order retry = service.placeOrder(command("sale-1", 3, "500.00"));

        assertThat(retry).isEqualTo(first);
        assertThat(inventory.stock.get(wrap.id())).isEqualTo(7);   // taken once
        assertThat(sequence.issued).isEqualTo(1);                   // one number
        assertThat(orders.saved).hasSize(1);
    }

    @Test
    void aDifferentKeyIsANewSale() {
        inventory.stock.put(wrap.id(), 10);

        Order first = service.placeOrder(command("sale-1", 1, "500.00"));
        Order second = service.placeOrder(command("sale-2", 1, "500.00"));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(inventory.stock.get(wrap.id())).isEqualTo(8);
    }

    @Test
    void ifAnotherRequestWithTheSameKeyWinsTheRaceWeReturnItsOrder() {
        inventory.stock.put(wrap.id(), 10);
        orders.anotherRequestWinsTheRace = true;

        Order returned = service.placeOrder(command("sale-1", 1, "500.00"));

        assertThat(returned).isEqualTo(orders.byKey.get("sale-1"));
    }

    @Test
    void aKeyMustBeSent() {
        assertThatThrownBy(() -> command(" ", 1, "500.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(null, 1, "500.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOrderWithFoodToPrepareWritesAKitchenEventAlongsideTheOrder() {
        inventory.stock.put(wrap.id(), 10);

        Order order = service.placeOrder(command(3, "500.00"));

        assertThat(outbox.appended).hasSize(1);
        assertThat(outbox.appended.get(0).orderId()).isEqualTo(order.id());
        assertThat(outbox.appended.get(0).items()).containsExactly(new OrderPlaced.Item("WRAP", "Wrap", 3));
    }

    @Test
    void anOrderWithNothingToPrepareWritesNoEvent() {
        Product bottle = new Product(Ids.newId(), "BOTTLE", "Water", Money.of("40.00", "INR"), TaxRate.of("0.05"), false);
        OrderService bottleService = new OrderService(new DirectTransactions(), new FakeCatalog(bottle), inventory,
                sequence, orders, outbox, branch, clock);
        inventory.stock.put(bottle.id(), 10);

        bottleService.placeOrder(new PlaceOrderCommand("sale-1", "KIOSK-07",
                List.of(new PlaceOrderCommand.Item(bottle.id(), new Quantity(1))),
                new Payment(PaymentMethod.CASH, Money.of("500.00", "INR"))));

        assertThat(outbox.appended).isEmpty();
    }

    @Test
    void aRetryDoesNotWriteASecondKitchenEvent() {
        inventory.stock.put(wrap.id(), 10);

        service.placeOrder(command("sale-1", 1, "500.00"));
        service.placeOrder(command("sale-1", 1, "500.00"));

        assertThat(outbox.appended).hasSize(1);
    }

    @Test
    void anOrderThatIsRefusedWritesNoEvent() {
        inventory.stock.put(wrap.id(), 1);

        assertThatThrownBy(() -> service.placeOrder(command(3, "500.00"))).isInstanceOf(OutOfStockException.class);

        assertThat(outbox.appended).isEmpty();
    }

    @Test
    void takesStockInProductIdOrderWhateverOrderTheCustomerListedIt() {
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID high = UUID.fromString("00000000-0000-7000-8000-000000000002");
        Product first = new Product(low, "AAA", "A", Money.of("10.00", "INR"), TaxRate.of("0.05"), false);
        Product second = new Product(high, "BBB", "B", Money.of("10.00", "INR"), TaxRate.of("0.05"), false);
        FakeCatalog twoProducts = new FakeCatalog(first, second);
        inventory.stock.put(first.id(), 5);
        inventory.stock.put(second.id(), 5);
        OrderService twoProductService = new OrderService(new DirectTransactions(), twoProducts, inventory, sequence, orders, outbox, branch, clock);

        // the customer lists the second product first
        twoProductService.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(), "KIOSK-07",
                List.of(new PlaceOrderCommand.Item(second.id(), new Quantity(1)),
                        new PlaceOrderCommand.Item(first.id(), new Quantity(1))),
                new Payment(PaymentMethod.CASH, Money.of("500.00", "INR"))));

        assertThat(inventory.takenInOrder).containsExactly(low, high);
    }



    private PlaceOrderCommand command(int quantity, String cash) {
        return command(UUID.randomUUID().toString(), quantity, cash);
    }

    private PlaceOrderCommand command(String idempotencyKey, int quantity, String cash) {
        return new PlaceOrderCommand(idempotencyKey, "KIOSK-07",
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
        final List<UUID> takenInOrder = new ArrayList<>();

        @Override
        public boolean tryTake(UUID productId, int quantity) {
            int available = stock.getOrDefault(productId, 0);
            if (available < quantity) {
                return false;
            }
            stock.put(productId, available - quantity);
            takenInOrder.add(productId);
            return true;
        }
    }

    private static class FakeOrders implements OrderStore {
        final List<Order> saved = new ArrayList<>();
        final Map<String, Order> byKey = new HashMap<>();
        boolean anotherRequestWinsTheRace = false;

        @Override
        public void save(Order order, String idempotencyKey) {
            if (anotherRequestWinsTheRace) {
                // pretend a second request with the same key saved its order a moment earlier
                saved.add(order);
                byKey.put(idempotencyKey, order);
                throw new DuplicateRequestException(idempotencyKey);
            }
            saved.add(order);
            byKey.put(idempotencyKey, order);
        }

        @Override
        public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return saved.stream().filter(order -> order.id().equals(id)).findFirst();
        }
    }

    private static class FakeOutbox implements OutboxStore {
        final List<OrderPlaced> appended = new ArrayList<>();

        @Override
        public void append(OrderPlaced event, Instant now) {
            appended.add(event);
        }

        @Override
        public Optional<PendingEvent> lockNextDue(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markPublished(UUID eventId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(UUID eventId, int attempts, String error, Instant nextAttemptAt, boolean giveUp) {
            throw new UnsupportedOperationException();
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
