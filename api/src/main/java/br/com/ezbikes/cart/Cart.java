package br.com.ezbikes.cart;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Carrinho em memória. Métodos sincronizados: o mesmo carrinho pode receber requisições paralelas. */
final class Cart {

    static final int MAX_QUANTITY_PER_ITEM = 10;
    static final int MAX_DISTINCT_ITEMS = 20;

    private final Map<Long, Integer> items = new LinkedHashMap<>();
    private Instant updatedAt;

    Cart(Instant now) {
        this.updatedAt = now;
    }

    synchronized void add(long productId, int quantity, Instant now) {
        if (!items.containsKey(productId) && items.size() >= MAX_DISTINCT_ITEMS) {
            throw new IllegalStateException("O carrinho aceita no máximo %d produtos diferentes".formatted(MAX_DISTINCT_ITEMS));
        }
        int newQuantity = items.getOrDefault(productId, 0) + quantity;
        if (newQuantity > MAX_QUANTITY_PER_ITEM) {
            throw new IllegalStateException("Máximo de %d unidades por produto".formatted(MAX_QUANTITY_PER_ITEM));
        }
        items.put(productId, newQuantity);
        updatedAt = now;
    }

    synchronized void remove(long productId, Instant now) {
        items.remove(productId);
        updatedAt = now;
    }

    synchronized Map<Long, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    synchronized Instant updatedAt() {
        return updatedAt;
    }
}
