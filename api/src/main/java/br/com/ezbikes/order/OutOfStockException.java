package br.com.ezbikes.order;

/** Estoque insuficiente para o pedido (HTTP 409). Regra de negócio: não consome o error budget. */
public class OutOfStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public OutOfStockException(String sku, int requested, int available) {
        super("Estoque insuficiente para %s: solicitado %d, disponível %d".formatted(sku, requested, available));
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
