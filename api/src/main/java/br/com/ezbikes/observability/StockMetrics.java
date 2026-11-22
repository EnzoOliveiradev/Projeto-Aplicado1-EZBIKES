package br.com.ezbikes.observability;

import br.com.ezbikes.catalog.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Gauge {@code ezbikes_product_stock{sku, category}}: unidades em estoque por produto.
 *
 * <p>O valor é atualizado em segundo plano a cada 15 s, em vez de consultar o banco a cada
 * scrape: o Prometheus nunca gera carga no PostgreSQL. A cardinalidade é limitada ao catálogo
 * (~20 SKUs); num catálogo grande, o correto seria expor só agregados (ex.: produtos com
 * estoque baixo) e deixar o detalhe por SKU para o banco ou para os logs.
 */
@Component
public class StockMetrics {

    private final ProductRepository products;
    private final MultiGauge stock;

    public StockMetrics(ProductRepository products, MeterRegistry registry) {
        this.products = products;
        this.stock = MultiGauge.builder("ezbikes.product.stock")
                .description("Unidades em estoque por produto")
                .register(registry);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT15S")
    @Transactional(readOnly = true)
    public void refresh() {
        stock.register(products.findAll().stream()
                .map(p -> MultiGauge.Row.of(
                        Tags.of("sku", p.getSku(), "category", p.getCategory().name().toLowerCase(Locale.ROOT)),
                        p.getStock()))
                .toList(), true);
    }
}
