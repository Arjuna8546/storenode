package com.storenode.application;

import com.storenode.application.port.ProductCatalog;

import java.util.List;

// application/CatalogService.java
public class CatalogService {

    private final ProductCatalog catalog;

    public CatalogService(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    public List<ProductAvailability> listProducts() {
        return catalog.findAllWithAvailability();
    }
}
