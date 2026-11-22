package br.com.ezbikes.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Comportamento do gateway simulado.
 *
 * @param failureRate  probabilidade (0 a 1) de o gateway falhar em cada cobrança
 * @param minLatencyMs latência mínima simulada do provedor
 * @param maxLatencyMs latência máxima simulada do provedor
 */
@ConfigurationProperties("ezbikes.payment")
public record PaymentProperties(double failureRate, int minLatencyMs, int maxLatencyMs) {

    public PaymentProperties {
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("ezbikes.payment.failure-rate deve estar entre 0 e 1");
        }
        if (minLatencyMs < 0 || maxLatencyMs < minLatencyMs) {
            throw new IllegalArgumentException("Latências do gateway inválidas");
        }
    }
}
