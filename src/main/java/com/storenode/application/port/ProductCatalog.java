package com.storenode.application.port;

// application/port/ProductCatalog.java

import com.storenode.application.ProductAvailability;
import com.storenode.domain.catalog.Product;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProductCatalog {

    List<ProductAvailability> findAllWithAvailability();

    /** The products that exist, by id. Ids with no product are simply missing from the map. */
    Map<UUID, Product> findByIds(Collection<UUID> productIds);

}

