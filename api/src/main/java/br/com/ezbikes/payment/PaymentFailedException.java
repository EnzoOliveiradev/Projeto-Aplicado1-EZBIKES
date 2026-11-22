package br.com.ezbikes.payment;

import java.util.UUID;

/**
 * Falha do gateway de pagamento (HTTP 502). Falha do serviço: consome o error budget.
 */
public class PaymentFailedException extends RuntimeException {

    private final String reason;
    private final UUID orderId;

    public PaymentFailedException(String reason) {
        this(reason, null, null);
    }

    private PaymentFailedException(String reason, UUID orderId, Throwable cause) {
        super("Falha no pagamento: " + reason, cause);
        this.reason = reason;
        this.orderId = orderId;
    }

    /** Devolve uma cópia associada ao pedido, para que a resposta informe qual pedido falhou. */
    public PaymentFailedException forOrder(UUID orderId) {
        return new PaymentFailedException(reason, orderId, this);
    }

    public String getReason() {
        return reason;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
