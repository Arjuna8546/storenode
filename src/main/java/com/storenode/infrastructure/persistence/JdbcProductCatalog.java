package com.storenode.infrastructure.persistence;

import com.storenode.application.ProductAvailability;
import com.storenode.application.port.ProductCatalog;
import com.storenode.domain.catalog.Product;
import com.storenode.domain.shared.Money;
import com.storenode.domain.shared.TaxRate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcProductCatalog implements ProductCatalog {

    private final JdbcClient jdbc;

    public JdbcProductCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProductAvailability> findAllWithAvailability() {
        return jdbc.sql("""
                        select p.id, p.sku, p.name, p.unit_price, p.currency, p.tax_rate, p.requires_preparation,
                               coalesce(i.quantity, 0) as quantity
                        from product p left join inventory i on i.product_id = p.id
                        order by p.name""")
                .query((rs, rowNum) -> new ProductAvailability(toProduct(rs), rs.getInt("quantity")))
                .list();
    }

    private static Product toProduct(ResultSet rs) throws SQLException {
        Money price = new Money(rs.getBigDecimal("unit_price"), Currency.getInstance(rs.getString("currency")));
        return new Product(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"), price,
                new TaxRate(rs.getBigDecimal("tax_rate")), rs.getBoolean("requires_preparation"));
    }
}

