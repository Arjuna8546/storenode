package com.storenode.web;

import com.storenode.application.CatalogService;
import com.storenode.web.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return catalog.listProducts().stream().map(ProductResponse::from).toList();
    }
}

