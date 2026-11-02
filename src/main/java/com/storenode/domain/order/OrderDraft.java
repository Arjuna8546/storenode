package com.storenode.domain.order;

import com.storenode.domain.catalog.Product;
import com.storenode.domain.exception.CurrencyMismatchException;
import com.storenode.domain.exception.InvalidOrderException;
import com.storenode.domain.exception.PaymentRejectedException;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;

import java.time.Instant;
import java.util.*;

public final class OrderDraft {

    public record RequestedLine(Product product, Quantity quantity) {
    }

    private final List<OrderLine> lines;
    private final Money subtotal;
    private final Money taxTotal;
    private final Money total;
    private final Payment payment;
    private final Money changeDue;

    private OrderDraft(List<OrderLine> lines, Money subtotal, Money taxTotal, Money total, Payment payment,
                       Money changeDue) {
        this.lines = List.copyOf(lines);
        this.subtotal = subtotal;
        this.taxTotal = taxTotal;
        this.total = total;
        this.payment = payment;
        this.changeDue = changeDue;
    }

    public static OrderDraft create(List<RequestedLine> requested, Payment payment) {
        if (requested == null || requested.isEmpty()) {
            throw new InvalidOrderException("An order needs at least one line");
        }
        if (payment == null) {
            throw new InvalidOrderException("An order needs a payment");
        }

        Currency currency = requested.get(0).product().unitPrice().currency();
        List<OrderLine> lines = new ArrayList<>();
        Money subtotal = Money.zero(currency);
        Money taxTotal = Money.zero(currency);

        int lineNo = 1;
        for (RequestedLine line : mergeSameProduct(requested)) {
            OrderLine priced = OrderLine.price(lineNo++, line.product(), line.quantity());
            lines.add(priced);
            subtotal = subtotal.plus(priced.netAmount());   // throws if a line is in another currency
            taxTotal = taxTotal.plus(priced.taxAmount());
        }

        Money total = subtotal.plus(taxTotal);
        Money changeDue = settle(payment, total);
        return new OrderDraft(lines, subtotal, taxTotal, total, payment, changeDue);
    }

//to manage same product
    private static List<RequestedLine> mergeSameProduct(List<RequestedLine> requested) {
        Map<UUID, RequestedLine> merged = new LinkedHashMap<>();
        for (RequestedLine line : requested) {
            merged.merge(line.product().id(), line,
                    (existing, extra) -> new RequestedLine(existing.product(), existing.quantity().plus(extra.quantity())));
        }
        return List.copyOf(merged.values());
    }

    private static Money settle(Payment payment, Money total) {
        Money tendered = payment.tendered();
        if (!tendered.hasSameCurrencyAs(total)) {
            throw new CurrencyMismatchException(total.currency().getCurrencyCode(),
                    tendered.currency().getCurrencyCode());
        }
        return switch (payment.method()) {
            case CARD -> {
                if (tendered.compareTo(total) != 0) {
                    throw new PaymentRejectedException(
                            "Card payment of " + tendered + " does not match the total " + total);
                }
                yield Money.zero(total.currency());
            }
            case CASH -> {
                if (tendered.isLessThan(total)) {
                    throw new PaymentRejectedException(
                            "Cash tendered " + tendered + " is less than the total " + total);
                }
                yield tendered.minus(total);
            }
        };
    }


    public Order place(UUID orderId, UUID branchId, OrderNumber number, String terminalId, Instant placedAt) {
        return new Order(orderId, branchId, number, terminalId, OrderStatus.PLACED, lines, subtotal, taxTotal,
                total, payment, changeDue, placedAt);
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Money subtotal() {
        return subtotal;
    }

    public Money taxTotal() {
        return taxTotal;
    }

    public Money total() {
        return total;
    }

    public Payment payment() {
        return payment;
    }

    public Money changeDue() {
        return changeDue;
    }
}
