package br.com.ezbikes.chaos;

import br.com.ezbikes.payment.PaymentProperties;
import org.springframework.stereotype.Component;

/**
 * Falhas injetadas em tempo de execução para os experimentos de confiabilidade
 * (engenharia do caos controlada). Alterado por {@code PUT /admin/chaos}.
 *
 * <ul>
 *   <li>{@code paymentFailureRate}: probabilidade de o gateway de pagamento falhar (502);</li>
 *   <li>{@code errorRate}: probabilidade de uma requisição a /api/** falhar com 503;</li>
 *   <li>{@code extraLatencyMs} + {@code latencyScope}: atraso adicional nas rotas escolhidas.</li>
 * </ul>
 */
@Component
public class ChaosState {

    public enum LatencyScope { ALL, CATALOG, CHECKOUT }

    public record Snapshot(double paymentFailureRate, double errorRate, int extraLatencyMs, LatencyScope latencyScope) {

        public boolean active() {
            return paymentFailureRate > 0 || errorRate > 0 || extraLatencyMs > 0;
        }
    }

    static final int MAX_EXTRA_LATENCY_MS = 10_000;

    private volatile Snapshot current;

    public ChaosState(PaymentProperties payment) {
        // Valor inicial vindo de EZBIKES_PAYMENT_FAILURE_RATE, para compatibilidade com o compose.
        this.current = new Snapshot(payment.failureRate(), 0, 0, LatencyScope.ALL);
    }

    public Snapshot snapshot() {
        return current;
    }

    public Snapshot update(double paymentFailureRate, double errorRate, int extraLatencyMs, LatencyScope scope) {
        requireProbability("paymentFailureRate", paymentFailureRate);
        requireProbability("errorRate", errorRate);
        if (extraLatencyMs < 0 || extraLatencyMs > MAX_EXTRA_LATENCY_MS) {
            throw new IllegalArgumentException("extraLatencyMs deve estar entre 0 e " + MAX_EXTRA_LATENCY_MS);
        }
        current = new Snapshot(paymentFailureRate, errorRate, extraLatencyMs, scope == null ? LatencyScope.ALL : scope);
        return current;
    }

    public Snapshot reset() {
        current = new Snapshot(0, 0, 0, LatencyScope.ALL);
        return current;
    }

    public double paymentFailureRate() {
        return current.paymentFailureRate();
    }

    private static void requireProbability(String name, double value) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " deve estar entre 0 e 1");
        }
    }
}
