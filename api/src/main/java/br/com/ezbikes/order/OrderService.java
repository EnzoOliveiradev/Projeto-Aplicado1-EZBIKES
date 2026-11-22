package br.com.ezbikes.order;

import br.com.ezbikes.cart.CartService;
import br.com.ezbikes.catalog.Product;
import br.com.ezbikes.catalog.ProductRepository;
import br.com.ezbikes.common.BusinessRuleException;
import br.com.ezbikes.common.NotFoundException;
import br.com.ezbikes.observability.PiiMasker;
import br.com.ezbikes.payment.PaymentFailedException;
import br.com.ezbikes.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Checkout em três etapas, cada uma com sua própria transação curta:
 *
 * <ol>
 *   <li><b>Reserva</b>: baixa o estoque de forma atômica e grava o pedido como CREATED;</li>
 *   <li><b>Pagamento</b>: chama o gateway <i>fora</i> de transação, para não segurar
 *       conexões do pool enquanto espera uma dependência externa;</li>
 *   <li><b>Conclusão</b>: marca PAID, ou PAYMENT_FAILED devolvendo o estoque.</li>
 * </ol>
 *
 * <p>Cada etapa emite um evento de negócio estruturado ({@code event.action}) com o
 * {@code order_id} no MDC, o que permite reconstruir a história de um pedido no Kibana.
 */
@Service
public class OrderService {

    static final int LOW_STOCK_THRESHOLD = 3;
    static final String ORDER_ID = "order_id";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

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

        try (var ignored = MDC.putCloseable(ORDER_ID, reserved.getId().toString())) {
            log.info("Pedido criado com estoque reservado",
                    kv("event.action", "order.created"),
                    kv("order_total", reserved.getTotal()),
                    kv("items_count", reserved.getItems().size()),
                    kv("customer_email", PiiMasker.maskEmail(reserved.getCustomerEmail())));

            try {
                payments.charge(reserved.getId(), reserved.getTotal());
            } catch (PaymentFailedException e) {
                tx.executeWithoutResult(status -> failAndReleaseStock(reserved.getId()));
                log.error("Falha no pagamento; pedido cancelado e estoque devolvido",
                        kv("event.action", "order.payment_failed"),
                        kv("payment_reason", e.getReason()),
                        kv("order_total", reserved.getTotal()));
                throw e.forOrder(reserved.getId());
            }

            OrderResponse paid = tx.execute(status -> markPaid(reserved.getId()));
            log.info("Pagamento aprovado",
                    kv("event.action", "order.paid"),
                    kv("order_total", paid.total()));

            if (request.cartId() != null) {
                carts.clear(request.cartId());
            }
            return paid;
        }
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
                log.warn("Pedido rejeitado por estoque insuficiente",
                        kv("event.action", "order.rejected_out_of_stock"),
                        kv("sku", product.getSku()),
                        kv("requested_qty", quantity),
                        kv("available_qty", product.getStock()));
                // A exceção desfaz a transação inteira, inclusive as baixas já feitas.
                throw new OutOfStockException(product.getSku(), quantity, product.getStock());
            }
            order.addItem(productId, product.getSku(), product.getPrice(), quantity);
            warnIfLowStock(productId, product.getSku());
        });
        return orders.save(order);
    }

    private void warnIfLowStock(long productId, String sku) {
        int remaining = products.findStockById(productId);
        if (remaining < LOW_STOCK_THRESHOLD) {
            log.warn("Estoque baixo",
                    kv("event.action", "stock.low"),
                    kv("sku", sku),
                    kv("stock_remaining", remaining));
        }
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
