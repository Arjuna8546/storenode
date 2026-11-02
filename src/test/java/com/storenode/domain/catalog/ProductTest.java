package com.storenode.domain.catalog;

import com.storenode.domain.shared.Ids;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.TaxRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    void aProductNeedsASku() {
        assertThatThrownBy(() -> product(" ", "10.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void aProductCannotHaveANegativePrice() {
        assertThatThrownBy(() -> product("WATER", "-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    private static Product product(String sku, String price) {
        return new Product(Ids.newId(), sku, "Water", Money.of(price, "INR"), TaxRate.of("0.12"), false);
    }
}

