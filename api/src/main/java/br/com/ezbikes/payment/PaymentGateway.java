package br.com.ezbikes.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** Porta para o provedor de pagamento. */
public interface PaymentGateway {

    /**
     * Cobra o valor do pedido.
     *
     * @throws PaymentFailedException quando o provedor falha ou não responde
     */
    void charge(UUID orderId, BigDecimal amount);
}
