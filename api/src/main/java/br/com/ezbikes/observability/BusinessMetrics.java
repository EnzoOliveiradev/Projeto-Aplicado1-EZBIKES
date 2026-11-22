package br.com.ezbikes.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Métricas de negócio da loja (expostas em /actuator/prometheus).
 *
 * <table>
 *   <tr><th>Prometheus</th><th>Tipo</th><th>Labels</th></tr>
 *   <tr><td>ezbikes_orders_total</td><td>Counter</td><td>status</td></tr>
 *   <tr><td>ezbikes_checkout_duration_seconds</td><td>Timer (histograma)</td><td>outcome</td></tr>
 *   <tr><td>ezbikes_order_value_reais</td><td>DistributionSummary</td><td>-</td></tr>
 *   <tr><td>ezbikes_cart_items_added_total</td><td>Counter</td><td>category</td></tr>
 *   <tr><td>ezbikes_payment_gateway_duration_seconds</td><td>Timer (histograma)</td><td>outcome</td></tr>
 *   <tr><td>ezbikes_payment_gateway_failures_total</td><td>Counter</td><td>reason</td></tr>
 *   <tr><td>ezbikes_product_stock</td><td>Gauge</td><td>sku, category ({@link StockMetrics})</td></tr>
 * </table>
 *
 * <p><b>Cardinalidade:</b> todos os labels têm conjuntos pequenos e fechados (status, resultado,
 * categoria, motivo, ~20 SKUs). Identificadores como order_id, cliente ou e-mail nunca viram
 * label: cada valor distinto criaria uma nova série temporal no Prometheus. Eles ficam nos logs.
 */
@Component
public class BusinessMetrics {

    /** Resultado do checkout; vira o label {@code status} / {@code outcome}. */
    public enum CheckoutOutcome {
        PAID, PAYMENT_FAILED, OUT_OF_STOCK, REJECTED, ERROR;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Map<CheckoutOutcome, Counter> orders = new EnumMap<>(CheckoutOutcome.class);
    private final Map<CheckoutOutcome, Timer> checkoutDuration = new EnumMap<>(CheckoutOutcome.class);
    private final DistributionSummary orderValue;
    private final Timer gatewaySuccess;
    private final Timer gatewayFailure;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Séries pré-registradas com zero: rate() e as recording rules funcionam desde o
        // primeiro scrape, mesmo antes de acontecer a primeira falha.
        for (CheckoutOutcome outcome : CheckoutOutcome.values()) {
            orders.put(outcome, Counter.builder("ezbikes.orders")
                    .description("Pedidos processados no checkout, por resultado")
                    .tag("status", outcome.tag())
                    .register(registry));
            checkoutDuration.put(outcome, Timer.builder("ezbikes.checkout.duration")
                    .description("Duração do checkout (reserva + pagamento + conclusão)")
                    .tag("outcome", outcome.tag())
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(10))
                    .maximumExpectedValue(Duration.ofSeconds(5))
                    .serviceLevelObjectives(Duration.ofMillis(300), Duration.ofSeconds(1))
                    .register(registry));
        }

        this.orderValue = DistributionSummary.builder("ezbikes.order.value")
                .description("Valor dos pedidos pagos (soma = receita; soma/contagem = ticket médio)")
                .baseUnit("reais")
                .register(registry);

        this.gatewaySuccess = gatewayTimer("success");
        this.gatewayFailure = gatewayTimer("failure");
        gatewayFailures("gateway_unavailable");
    }

    public Timer.Sample startCheckout() {
        return Timer.start(registry);
    }

    public void checkoutFinished(Timer.Sample sample, CheckoutOutcome outcome) {
        sample.stop(checkoutDuration.get(outcome));
        orders.get(outcome).increment();
    }

    public void orderPaid(BigDecimal total) {
        orderValue.record(total.doubleValue());
    }

    public void cartItemAdded(String category, int quantity) {
        Counter.builder("ezbikes.cart.items.added")
                .description("Unidades adicionadas ao carrinho, por categoria")
                .tag("category", category.toLowerCase(Locale.ROOT))
                .register(registry)
                .increment(quantity);
    }

    /**
     * Mede a chamada ao gateway de pagamento (dependência externa) e conta as falhas por motivo.
     */
    public void recordGatewayCall(Runnable call, Function<RuntimeException, String> failureReason) {
        Timer.Sample sample = Timer.start(registry);
        try {
            call.run();
            sample.stop(gatewaySuccess);
        } catch (RuntimeException e) {
            sample.stop(gatewayFailure);
            gatewayFailures(failureReason.apply(e)).increment();
            throw e;
        }
    }

    private Timer gatewayTimer(String outcome) {
        return Timer.builder("ezbikes.payment.gateway.duration")
                .description("Latência das chamadas ao gateway de pagamento")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);
    }

    private Counter gatewayFailures(String reason) {
        return Counter.builder("ezbikes.payment.gateway.failures")
                .description("Falhas do gateway de pagamento, por motivo")
                .tag("reason", reason)
                .register(registry);
    }
}
