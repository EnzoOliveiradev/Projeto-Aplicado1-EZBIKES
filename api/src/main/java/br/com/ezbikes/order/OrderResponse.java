package br.com.ezbikes.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Representação pública do pedido. O e-mail do cliente não é exposto. */
public record OrderResponse(UUID id, OrderStatus status, String customerName, BigDecimal total,
                            Instant createdAt, List<Item> items) {

    public record Item(Long productId, String sku, BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
    }

    static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getSku(), i.getUnitPrice(), i.getQuantity(), i.getSubtotal()))
                .toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getCustomerName(), order.getTotal(),
                order.getCreatedAt(), items);
    }
}
