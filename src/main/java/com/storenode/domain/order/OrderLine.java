package com.storenode.domain.order;

import com.storenode.domain.catalog.Product;
import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.Quantity;
import com.storenode.domain.shared.TaxRate;

import java.util.Objects;
import java.util.UUID;

public record OrderLine(UUID id, int lineNo, UUID productId, String sku, String name, Money unitPrice,
                        Quantity quantity, TaxRate taxRate, Money netAmount, Money taxAmount, Money lineTotal,
                        boolean requiresPreparation) {

    public OrderLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(taxRate, "taxRate");
        if (!netAmount.plus(taxAmount).equals(lineTotal)) {
            throw new IllegalArgumentException("Line total must equal net + tax");
        }
    }

    static OrderLine price(int lineNo, Product product, Quantity quantity) {
        Money net = product.unitPrice().times(quantity.value()).roundToMinorUnit();
        Money tax = product.taxRate().taxOn(net);
        return new OrderLine(Ids.newId(), lineNo, product.id(), product.sku(), product.name(), product.unitPrice(),
                quantity, product.taxRate(), net, tax, net.plus(tax), product.requiresPreparation());
    }
}

