package br.com.ezbikes.order;

import br.com.ezbikes.cart.CartService;
import br.com.ezbikes.catalog.Product;
import br.com.ezbikes.catalog.ProductRepository;
import br.com.ezbikes.common.BusinessRuleException;
import br.com.ezbikes.common.NotFoundException;
import br.com.ezbikes.payment.PaymentFailedException;
import br.com.ezbikes.payment.PaymentGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checkout em três etapas, cada uma com sua própria transação curta:
 *
 * <ol>
 *   <li><b>Reserva</b>: baixa o estoque de forma atômica e grava o pedido como CREATED;</li>
 *   <li><b>Pagamento</b>: chama o gateway <i>fora</i> de transação, para não segurar
 *       conexões do pool enquanto espera uma dependência externa;</li>
 *   <li><b>Conclusão</b>: marca PAID, ou PAYMENT_FAILED devolvendo o estoque.</li>
 * </ol>
 */
@Service
public class OrderService {

    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentGateway payments;
    private final CartService carts;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(ProductRepository products, OrderRepository orders, PaymentGateway payments,
                        CartService carts, TransactionTemplate tx, Clock clock) {
        this.products = products;
        this.orders = orders;
        this.payments = payments;
        this.carts = carts;
        this.tx = tx;
        this.clock = clock;
    }

    public OrderResponse placeOrder(PlaceOrderRequest request) {
        Map<Long, Integer> quantities = resolveItems(request);

        Order reserved = tx.execute(status -> reserve(request, quantities));

        try {
            payments.charge(reserved.getId(), reserved.getTotal());
        } catch (PaymentFailedException e) {
            tx.executeWithoutResult(status -> failAndReleaseStock(reserved.getId()));
            throw e.forOrder(reserved.getId());
        }

        OrderResponse paid = tx.execute(status -> markPaid(reserved.getId()));
        if (request.cartId() != null) {
            carts.clear(request.cartId());
        }
        return paid;
    }

    public OrderResponse get(UUID id) {
        return tx.execute(status -> orders.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new NotFoundException("Pedido %s não encontrado".formatted(id))));
    }

    /**
     * Junta itens repetidos e ordena por productId. A ordem fixa das baixas de estoque
     * evita deadlock entre dois pedidos que compram os mesmos produtos.
     */
    private Map<Long, Integer> resolveItems(PlaceOrderRequest request) {
        Map<Long, Integer> quantities = new TreeMap<>();
        if (request.items() != null && !request.items().isEmpty()) {
            request.items().forEach(i -> quantities.merge(i.productId(), i.quantity(), Integer::sum));
        } else if (request.cartId() != null) {
            quantities.putAll(carts.items(request.cartId()));
        }
        if (quantities.isEmpty()) {
            throw new BusinessRuleException("Informe os itens do pedido ou um carrinho com itens");
        }
        return quantities;
    }

    private Order reserve(PlaceOrderRequest request, Map<Long, Integer> quantities) {
        Map<Long, Product> byId = products.findAllById(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Set<Long> missing = quantities.keySet().stream().filter(id -> !byId.containsKey(id)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new NotFoundException("Produtos não encontrados: " + missing);
        }

        Order order = new Order(request.customerName().strip(), request.customerEmail().strip(), clock.instant());
        quantities.forEach((productId, quantity) -> {
            Product product = byId.get(productId);
            if (products.decrementStock(productId, quantity) == 0) {
                // A exceção desfaz a transação inteira, inclusive as baixas já feitas.
                throw new OutOfStockException(product.getSku(), quantity, product.getStock());
            }
            order.addItem(productId, product.getSku(), product.getPrice(), quantity);
        });
        return orders.save(order);
    }

    private void failAndReleaseStock(UUID orderId) {
        Order order = orders.findWithItemsById(orderId).orElseThrow();
        // Muda o status antes das devoluções: cada UPDATE de estoque faz flush e limpa o
        // contexto de persistência, e uma alteração feita depois disso não seria gravada.
        order.markPaymentFailed(clock.instant());
        order.getItems().forEach(item -> products.incrementStock(item.getProductId(), item.getQuantity()));
    }

    private OrderResponse markPaid(UUID orderId) {
        Order order = orders.findWithItemsById(orderId).orElseThrow();
        order.markPaid(clock.instant());
        return OrderResponse.from(order);
    }
}
