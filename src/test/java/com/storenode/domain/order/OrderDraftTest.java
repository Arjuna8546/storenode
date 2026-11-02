package com.storenode.domain.order;

import com.storenode.domain.catalog.Product;
import com.storenode.domain.exception.CurrencyMismatchException;
import com.storenode.domain.exception.InvalidOrderException;
import com.storenode.domain.exception.PaymentRejectedException;
import com.storenode.domain.order.OrderDraft.RequestedLine;
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

/** Business rules of pricing and payment, tested as plain Java: no Spring, no database. */
class OrderDraftTest {

    private final Product wrap = product("WRAP", "4.50", "INR", "0.05");
    private final Product water = product("WATER", "1.99", "INR", "0.12");

    @Test
    void pricesEachLineAndAddsUpTheTotals() {
        OrderDraft draft = OrderDraft.create(List.of(line(wrap, 2), line(water, 1)), card("11.68"));

        // wrap:  2 x 4.50 = 9.00, tax 5%  = 0.45
        // water: 1 x 1.99 = 1.99, tax 12% = 0.2388 -> 0.24
        assertThat(draft.lines()).extracting(OrderLine::taxAmount)
                .containsExactly(Money.of("0.45", "INR"), Money.of("0.24", "INR"));
        assertThat(draft.subtotal()).isEqualTo(Money.of("10.99", "INR"));
        assertThat(draft.taxTotal()).isEqualTo(Money.of("0.69", "INR"));
        assertThat(draft.total()).isEqualTo(Money.of("11.68", "INR"));
    }

    @Test
    void roundsEachLinesNetAmountToTheMinorUnit() {
        Product odd = product("ODD", "0.3333", "INR", "0");

        OrderDraft draft = OrderDraft.create(List.of(line(odd, 3)), cash("5"));

        // 3 x 0.3333 = 0.9999 -> 1.00
        assertThat(draft.lines().get(0).netAmount()).isEqualTo(Money.of("1.00", "INR"));
    }

    @Test
    void usesTheCurrencysOwnNumberOfDecimals() {
        Product ramen = product("RAMEN", "1250", "JPY", "0.10");

        OrderDraft draft = OrderDraft.create(List.of(line(ramen, 3)), new Payment(PaymentMethod.CARD, Money.of("4125", "JPY")));

        assertThat(draft.subtotal()).isEqualTo(Money.of("3750", "JPY"));
        assertThat(draft.taxTotal()).isEqualTo(Money.of("375", "JPY"));
        assertThat(draft.total().toPlainString()).isEqualTo("4125");
    }

    @Test
    void twoLinesForTheSameProductBecomeOne() {
        OrderDraft draft = OrderDraft.create(List.of(line(wrap, 1), line(water, 1), line(wrap, 2)), cash("100"));

        assertThat(draft.lines()).hasSize(2);
        assertThat(draft.lines().get(0).sku()).isEqualTo("WRAP");
        assertThat(draft.lines().get(0).quantity()).isEqualTo(new Quantity(3));
        assertThat(draft.lines().get(1).lineNo()).isEqualTo(2);
    }

    @Test
    void cashPaymentsGiveChange() {
        OrderDraft draft = OrderDraft.create(List.of(line(wrap, 2)), cash("20.00"));

        assertThat(draft.changeDue()).isEqualTo(Money.of("10.55", "INR"));
    }

    @Test
    void cashPaymentsMustCoverTheTotal() {
        assertThatThrownBy(() -> OrderDraft.create(List.of(line(wrap, 2)), cash("9.00")))
                .isInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void cardPaymentsMustBeForTheExactTotal() {
        assertThatThrownBy(() -> OrderDraft.create(List.of(line(wrap, 2)), card("10.00")))
                .isInstanceOf(PaymentRejectedException.class);

        assertThat(OrderDraft.create(List.of(line(wrap, 2)), card("9.45")).changeDue().isZero()).isTrue();
    }

    @Test
    void refusesLinesInDifferentCurrencies() {
        Product dollars = product("USD-ITEM", "1.00", "USD", "0");

        assertThatThrownBy(() -> OrderDraft.create(List.of(line(wrap, 1), line(dollars, 1)), cash("100")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesAPaymentInAnotherCurrency() {
        Payment dollars = new Payment(PaymentMethod.CASH, Money.of("100", "USD"));

        assertThatThrownBy(() -> OrderDraft.create(List.of(line(wrap, 1)), dollars))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void anOrderNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> OrderDraft.create(List.of(), cash("1"))).isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void placingTheDraftKeepsEveryAmount() {
        OrderDraft draft = OrderDraft.create(List.of(line(wrap, 2)), cash("20.00"));
        OrderNumber number = new OrderNumber("AIR01", LocalDate.of(2026, 11, 2), 1);

        Order order = draft.place(Ids.newId(), Ids.newId(), number, "KIOSK-07", Instant.parse("2026-11-02T05:00:00Z"));

        assertThat(order.number().format()).isEqualTo("ORD-AIR01-20261102-0001");
        assertThat(order.total()).isEqualTo(draft.total());
        assertThat(order.changeDue()).isEqualTo(Money.of("10.55", "INR"));
        assertThat(order.businessDate()).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    private static Product product(String sku, String price, String currency, String taxRate) {
        return new Product(Ids.newId(), sku, sku + " name", Money.of(price, currency), TaxRate.of(taxRate), false);
    }

    private static RequestedLine line(Product product, int quantity) {
        return new RequestedLine(product, new Quantity(quantity));
    }

    private static Payment cash(String amount) {
        return new Payment(PaymentMethod.CASH, Money.of(amount, "INR"));
    }

    private static Payment card(String amount) {
        return new Payment(PaymentMethod.CARD, Money.of(amount, "INR"));
    }
}

