package br.com.ezbikes.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gateway simulado: não movimenta dinheiro. Introduz latência e uma taxa de falha
 * configurável para que o checkout tenha comportamento realista de dependência externa.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private final PaymentProperties properties;

    public FakePaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void charge(UUID orderId, BigDecimal amount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        sleep(random.nextInt(properties.minLatencyMs(), properties.maxLatencyMs() + 1));

        if (random.nextDouble() < properties.failureRate()) {
            throw new PaymentFailedException("gateway_unavailable");
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentFailedException("interrupted");
        }
    }
}
