package br.com.ezbikes.order;

public enum OrderStatus {
    /** Estoque reservado, aguardando o pagamento. */
    CREATED,
    /** Pagamento aprovado. */
    PAID,
    /** O gateway falhou; o estoque reservado foi devolvido. */
    PAYMENT_FAILED
}
