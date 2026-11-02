package com.storenode.domain.order;

import com.storenode.domain.catalog.Product;
import com.storenode.domain.order.OrderDraft.RequestedLine;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.storenode.domain.shared.TaxRate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An Order re-checks its own arithmetic, so a wrong one cannot be built even from database rows. */
class OrderTest {

    private final Product wrap = new Product(Ids.newId(), "WRAP", "Wrap", Money.of("4.50", "INR"), TaxRate.of("0.05"), true);
    private final OrderNumber number = new OrderNumber("AIR01", LocalDate.of(2026, 11, 2), 1);

    private OrderDraft draft(Payment payment) {
        return OrderDraft.create(List.of(new RequestedLine(wrap, new Quantity(2))), payment);
    }

    private Order rebuilt(OrderDraft draft, Money total, Money changeDue) {
        return new Order(Ids.newId(), Ids.newId(), number, "KIOSK-07", OrderStatus.PLACED, draft.lines(),
                draft.subtotal(), draft.taxTotal(), total, draft.payment(), changeDue, Instant.now());
    }

    @Test
    void refusesATotalThatIsNotSubtotalPlusTax() {
        OrderDraft draft = draft(new Payment(PaymentMethod.CASH, Money.of("20", "INR")));

        assertThatThrownBy(() -> rebuilt(draft, Money.of("9.46", "INR"), Money.of("10.54", "INR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subtotal");
    }

    @Test
    void refusesChangeThatDoesNotMatchTheCashHandedOver() {
        OrderDraft draft = draft(new Payment(PaymentMethod.CASH, Money.of("20", "INR")));

        assertThatThrownBy(() -> rebuilt(draft, draft.total(), Money.of("10.00", "INR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Change");
    }

    @Test
    void refusesACardOrderWithChange() {
        OrderDraft draft = draft(new Payment(PaymentMethod.CARD, Money.of("9.45", "INR")));

        assertThatThrownBy(() -> rebuilt(draft, draft.total(), Money.of("1.00", "INR")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

