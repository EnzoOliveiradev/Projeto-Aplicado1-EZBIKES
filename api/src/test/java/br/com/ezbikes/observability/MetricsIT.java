package br.com.ezbikes.observability;

import br.com.ezbikes.IntegrationTest;
import br.com.ezbikes.cart.CartService;
import br.com.ezbikes.common.BusinessRuleException;
import br.com.ezbikes.order.OrderService;
import br.com.ezbikes.order.OutOfStockException;
import br.com.ezbikes.order.PlaceOrderRequest;
import br.com.ezbikes.payment.PaymentFailedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Métricas de negócio: cada resultado do checkout incrementa a série certa e os nomes
 * expostos ao Prometheus são os usados pelas recording rules e dashboards.
 * O registro é compartilhado entre os testes, por isso as verificações usam diferenças (delta).
 */
class MetricsIT extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    PrometheusMeterRegistry prometheus;

    @Autowired
    OrderService orders;

    @Autowired
    CartService carts;

    @Autowired
    StockMetrics stockMetrics;

    @Autowired
    MockMvc mvc;

    @Test
    void pedidoPagoRegistraContadorTimerValorEGateway() {
        double paid = counter("ezbikes.orders", "status", "paid");
        long timerCount = timerCount("ezbikes.checkout.duration", "outcome", "paid");
        long valueCount = registry.get("ezbikes.order.value").summary().count();
        double valueTotal = registry.get("ezbikes.order.value").summary().totalAmount();
        long gatewayOk = timerCount("ezbikes.payment.gateway.duration", "outcome", "success");

        orders.placeOrder(order(2, 1)); // Caloi Vulcan 29: R$ 1.899,90

        assertThat(counter("ezbikes.orders", "status", "paid")).isEqualTo(paid + 1);
        assertThat(timerCount("ezbikes.checkout.duration", "outcome", "paid")).isEqualTo(timerCount + 1);
        assertThat(registry.get("ezbikes.order.value").summary().count()).isEqualTo(valueCount + 1);
        assertThat(registry.get("ezbikes.order.value").summary().totalAmount())
                .isCloseTo(valueTotal + 1899.90, within(0.001));
        assertThat(timerCount("ezbikes.payment.gateway.duration", "outcome", "success")).isEqualTo(gatewayOk + 1);
    }

    @Test
    void falhaNoGatewayContaPedidoEFalhaPorMotivo() {
        doThrow(new PaymentFailedException("gateway_unavailable")).when(paymentGateway).charge(any(), any());
        double failed = counter("ezbikes.orders", "status", "payment_failed");
        double gatewayFailures = counter("ezbikes.payment.gateway.failures", "reason", "gateway_unavailable");
        long gatewayFailureTimer = timerCount("ezbikes.payment.gateway.duration", "outcome", "failure");

        assertThatThrownBy(() -> orders.placeOrder(order(1, 1))).isInstanceOf(PaymentFailedException.class);

        assertThat(counter("ezbikes.orders", "status", "payment_failed")).isEqualTo(failed + 1);
        assertThat(counter("ezbikes.payment.gateway.failures", "reason", "gateway_unavailable")).isEqualTo(gatewayFailures + 1);
        assertThat(timerCount("ezbikes.payment.gateway.duration", "outcome", "failure")).isEqualTo(gatewayFailureTimer + 1);
    }

    @Test
    void estoqueInsuficienteEPedidoInvalidoTemResultadosProprios() {
        setStock(1, 0);
        assertDelta("ezbikes.orders", "status", "out_of_stock",
                () -> assertThatThrownBy(() -> orders.placeOrder(order(1, 1))).isInstanceOf(OutOfStockException.class));

        assertDelta("ezbikes.orders", "status", "rejected",
                () -> assertThatThrownBy(() -> orders.placeOrder(
                        new PlaceOrderRequest("Ana", "ana@example.com", null, List.of())))
                        .isInstanceOf(BusinessRuleException.class));
    }

    @Test
    void carrinhoContaUnidadesPorCategoria() {
        Supplier<Double> mtb = () -> {
            var c = registry.find("ezbikes.cart.items.added").tag("category", "mtb").counter();
            return c == null ? 0.0 : c.count();
        };
        double before = mtb.get();

        carts.addItem(UUID.randomUUID().toString(), 1, 3); // Caloi Explorer (MTB)

        assertThat(mtb.get()).isEqualTo(before + 3);
    }

    @Test
    void gaugeDeEstoqueRefleteOBanco() {
        setStock(1, 4);
        stockMetrics.refresh();

        assertThat(registry.get("ezbikes.product.stock").tags("sku", "MTB-CAL-EXP", "category", "mtb").gauge().value())
                .isEqualTo(4.0);
    }

    @Test
    void scrapeExpoeNomesUsadosPelasRecordingRules() throws Exception {
        mvc.perform(get("/api/products"));
        orders.placeOrder(order(3, 1));
        stockMetrics.refresh();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .contains("ezbikes_orders_total{")
                .contains("status=\"paid\"")
                .contains("ezbikes_checkout_duration_seconds_bucket{")
                .contains("ezbikes_order_value_reais_sum")
                .contains("ezbikes_payment_gateway_failures_total{")
                .contains("ezbikes_product_stock{")
                .contains("application=\"ezbikes-api\"");
        // Bucket exato do SLO de latência do catálogo (usado em catalog_fast_requests)
        assertThat(scrape.lines()
                .filter(l -> l.startsWith("http_server_requests_seconds_bucket{"))
                .filter(l -> l.contains("uri=\"/api/products\""))
                .anyMatch(l -> l.contains("le=\"0.3\""))).isTrue();
    }

    private void assertDelta(String name, String tag, String value, Runnable action) {
        double before = counter(name, tag, value);
        action.run();
        assertThat(counter(name, tag, value)).isEqualTo(before + 1);
    }

    private double counter(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    private long timerCount(String name, String tag, String value) {
        var timer = Search.in(registry).name(name).tag(tag, value).timer();
        return timer == null ? 0 : timer.count();
    }

    private static PlaceOrderRequest order(long productId, int quantity) {
        return new PlaceOrderRequest("Cliente", "cliente@example.com", null,
                List.of(new PlaceOrderRequest.Item(productId, quantity)));
    }
}
