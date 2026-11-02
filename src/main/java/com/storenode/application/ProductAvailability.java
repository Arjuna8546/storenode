package com.storenode.application;

import com.storenode.domain.catalog.Product;

/** A product together with how many units are in stock right now. A read model for the menu. */
public record ProductAvailability(Product product, int stock) {
}
