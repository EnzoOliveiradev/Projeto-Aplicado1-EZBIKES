package br.com.ezbikes.catalog;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String brand,
        Category category,
        BigDecimal price,
        int stock,
        String description) {

    static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getBrand(),
                p.getCategory(), p.getPrice(), p.getStock(), p.getDescription());
    }
}
