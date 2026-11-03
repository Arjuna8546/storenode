package com.storenode.domain.event;

import com.storenode.domain.catalog.Product;
import com.storenode.domain.order.Order;
import com.storenode.domain.order.OrderDraft;
import com.storenode.domain.order.OrderDraft.RequestedLine;
import com.storenode.domain.order.OrderNumber;
import com.storenode.domain.order.Payment;
import com.storenode.domain.order.PaymentMethod;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.storenode.domain.shared.TaxRate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPlacedTest {

    private final Product meal = new Product(Ids.newId(), "MEAL", "Meal box", Money.of("100.00", "INR"), TaxRate.of("0.05"), true);
    private final Product water = new Product(Ids.newId(), "WATER", "Water", Money.of("40.00", "INR"), TaxRate.of("0.05"), false);

    @Test
    void onlyTheLinesThatNeedPreparingGoToTheKitchen() {
        Order order = order(new RequestedLine(meal, new Quantity(2)), new RequestedLine(water, new Quantity(5)));

        OrderPlaced event = OrderPlaced.from(order).orElseThrow();

        assertThat(event.orderId()).isEqualTo(order.id());
        assertThat(event.orderNumber()).isEqualTo(order.number().format());
        assertThat(event.items()).containsExactly(new OrderPlaced.Item("MEAL", "Meal box", 2));
    }

    @Test
    void anOrderOfPackagedGoodsOnlyMakesNoEvent() {
        Order order = order(new RequestedLine(water, new Quantity(1)));

        assertThat(OrderPlaced.from(order)).isEmpty();
    }

    @Test
    void anEventWithNothingToPrepareIsNotAllowed() {
        assertThatThrownBy(() -> new OrderPlaced(Ids.newId(), "ORD-AIR01-20261103-0001", Instant.now(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Order order(RequestedLine... lines) {
        OrderDraft draft = OrderDraft.create(List.of(lines), new Payment(PaymentMethod.CASH, Money.of("10000.00", "INR")));
        return draft.place(Ids.newId(), Ids.newId(), new OrderNumber("AIR01", LocalDate.of(2026, 11, 3), 1), "KIOSK-07", Instant.now());
    }
}
