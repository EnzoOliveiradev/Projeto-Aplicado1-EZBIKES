package br.com.ezbikes.cart;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(String cartId, List<Item> items, BigDecimal total) {

    public record Item(Long productId, String sku, String name, BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
    }

    static CartResponse empty(String cartId) {
        return new CartResponse(cartId, List.of(), BigDecimal.ZERO);
    }
}
