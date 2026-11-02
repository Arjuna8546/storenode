package com.storenode.application.port;

// application/port/ProductCatalog.java

import com.storenode.application.ProductAvailability;

import java.util.List;

public interface ProductCatalog {

    List<ProductAvailability> findAllWithAvailability();
}

