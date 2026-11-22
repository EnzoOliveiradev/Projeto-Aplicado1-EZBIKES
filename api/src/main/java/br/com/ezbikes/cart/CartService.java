package br.com.ezbikes.cart;

import br.com.ezbikes.catalog.Product;
import br.com.ezbikes.catalog.ProductRepository;
import br.com.ezbikes.common.BusinessRuleException;
import br.com.ezbikes.common.NotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Carrinhos mantidos em memória, identificados por um cartId gerado no navegador.
 *
 * <p>Simplificação consciente: com mais de uma réplica da API o carrinho não seria
 * compartilhado (seria necessário Redis ou banco). Carrinhos ociosos expiram para
 * não crescer sem limite durante os testes de carga.
 */
@Service
public class CartService {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, Cart> carts = new ConcurrentHashMap<>();
    private final ProductRepository products;
    private final Clock clock;

    public CartService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CartResponse addItem(String cartId, long productId, int quantity) {
        if (!products.existsById(productId)) {
            throw new NotFoundException("Produto %d não encontrado".formatted(productId));
        }
        Instant now = clock.instant();
        Cart cart = carts.computeIfAbsent(cartId, id -> new Cart(now));
        try {
            cart.add(productId, quantity, now);
        } catch (IllegalStateException e) {
            throw new BusinessRuleException(e.getMessage());
        }
        return view(cartId, cart.snapshot());
    }

    @Transactional(readOnly = true)
    public CartResponse removeItem(String cartId, long productId) {
        Cart cart = carts.get(cartId);
        if (cart == null) {
            return CartResponse.empty(cartId);
        }
        cart.remove(productId, clock.instant());
        return view(cartId, cart.snapshot());
    }

    @Transactional(readOnly = true)
    public CartResponse get(String cartId) {
        Cart cart = carts.get(cartId);
        return cart == null ? CartResponse.empty(cartId) : view(cartId, cart.snapshot());
    }

    /** Itens do carrinho (productId → quantidade), usados no checkout. */
    public Map<Long, Integer> items(String cartId) {
        Cart cart = carts.get(cartId);
        return cart == null ? Map.of() : cart.snapshot();
    }

    public void clear(String cartId) {
        carts.remove(cartId);
    }

    @Scheduled(fixedDelayString = "PT5M")
    void evictIdleCarts() {
        Instant limit = clock.instant().minus(IDLE_TIMEOUT);
        carts.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(limit));
    }

    int size() {
        return carts.size();
    }

    private CartResponse view(String cartId, Map<Long, Integer> items) {
        Map<Long, Product> byId = products.findAllById(items.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CartResponse.Item> lines = items.entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()))
                .map(e -> {
                    Product p = byId.get(e.getKey());
                    BigDecimal subtotal = p.getPrice().multiply(BigDecimal.valueOf(e.getValue()));
                    return new CartResponse.Item(p.getId(), p.getSku(), p.getName(), p.getPrice(), e.getValue(), subtotal);
                })
                .toList();

        BigDecimal total = lines.stream().map(CartResponse.Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cartId, lines, total);
    }
}
