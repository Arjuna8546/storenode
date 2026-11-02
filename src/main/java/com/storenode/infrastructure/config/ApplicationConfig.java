package com.storenode.infrastructure.config;

import com.storenode.application.CatalogService;
import com.storenode.application.port.ProductCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {

    @Bean
    CatalogService catalogService(ProductCatalog catalog) {
        return new CatalogService(catalog);
    }
}
