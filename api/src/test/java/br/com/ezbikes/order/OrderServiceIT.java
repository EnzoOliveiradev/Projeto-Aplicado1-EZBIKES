package br.com.ezbikes.order;

import br.com.ezbikes.IntegrationTest;
import br.com.ezbikes.cart.CartService;
import br.com.ezbikes.common.BusinessRuleException;
import br.com.ezbikes.common.NotFoundException;
import br.com.ezbikes.payment.PaymentFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class OrderServiceIT extends IntegrationTest {

    private static final long CALOI_EXPLORER = 1L; // R$ 4.299,90
    private static final long CALOI_VULCAN = 2L;   // R$ 1.899,90

    @Autowired
    OrderService orders;

    @Autowired
    CartService carts;

    @Test
    void pedidoPagoBaixaEstoqueECalculaTotal() {
        OrderResponse order = orders.placeOrder(request(
                item(CALOI_EXPLORER, 2), item(CALOI_VULCAN, 1)));

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.total()).isEqualByComparingTo(new BigDecimal("10499.70"));
        assertThat(order.items()).hasSize(2);
        assertThat(stockOf(CALOI_EXPLORER)).isEqualTo(8);
        assertThat(stockOf(CALOI_VULCAN)).isEqualTo(9);
    }

    @Test
    void itensRepetidosSaoSomados() {
        OrderResponse order = orders.placeOrder(request(item(CALOI_EXPLORER, 1), item(CALOI_EXPLORER, 2)));

        assertThat(order.items()).singleElement().satisfies(i -> assertThat(i.quantity()).isEqualTo(3));
        assertThat(stockOf(CALOI_EXPLORER)).isEqualTo(7);
    }

    @Test
    void estoqueInsuficienteNaoGravaPedidoNemBaixaOutrosItens() {
        setStock(CALOI_VULCAN, 1);

        assertThatThrownBy(() -> orders.placeOrder(request(item(CALOI_EXPLORER, 2), item(CALOI_VULCAN, 3))))
                .isInstanceOf(OutOfStockException.class)
                .hasMessageContaining("MTB-CAL-VUL");

        assertThat(stockOf(CALOI_EXPLORER)).isEqualTo(10);
        assertThat(stockOf(CALOI_VULCAN)).isEqualTo(1);
        assertThat(countOrders()).isZero();
    }

    @Test
    void falhaNoPagamentoMarcaPedidoEDevolveEstoque() {
        doThrow(new PaymentFailedException("gateway_unavailable")).when(paymentGateway).charge(any(), any());

        UUID[] failedOrderId = new UUID[1];
        assertThatThrownBy(() -> orders.placeOrder(request(item(CALOI_EXPLORER, 3))))
                .isInstanceOfSatisfying(PaymentFailedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("gateway_unavailable");
                    assertThat(e.getOrderId()).isNotNull();
                    failedOrderId[0] = e.getOrderId();
                });

        assertThat(orders.get(failedOrderId[0]).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stockOf(CALOI_EXPLORER)).isEqualTo(10);
    }

    @Test
    void checkoutPeloCarrinhoEsvaziaOCarrinho() {
        String cartId = UUID.randomUUID().toString();
        carts.addItem(cartId, CALOI_VULCAN, 2);

        OrderResponse order = orders.placeOrder(new PlaceOrderRequest("Ana", "ana@example.com", cartId, null));

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(stockOf(CALOI_VULCAN)).isEqualTo(8);
        assertThat(carts.get(cartId).items()).isEmpty();
    }

    @Test
    void pedidoSemItensERejeitado() {
        assertThatThrownBy(() -> orders.placeOrder(new PlaceOrderRequest("Ana", "ana@example.com", null, List.of())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void produtoInexistenteERejeitado() {
        assertThatThrownBy(() -> orders.placeOrder(request(item(9999L, 1))))
                .isInstanceOf(NotFoundException.class);
        assertThat(countOrders()).isZero();
    }

    @Test
    void checkoutsConcorrentesNaoVendemMaisQueOEstoque() throws Exception {
        setStock(CALOI_EXPLORER, 5);
        int buyers = 20;
        AtomicInteger outOfStock = new AtomicInteger();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            tasks.add(() -> {
                try {
                    orders.placeOrder(request(item(CALOI_EXPLORER, 1)));
                    return true;
                } catch (OutOfStockException e) {
                    outOfStock.incrementAndGet();
                    return false;
                }
            });
        }

        int paid = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get()) {
                    paid++;
                }
            }
        }

        assertThat(paid).isEqualTo(5);
        assertThat(outOfStock.get()).isEqualTo(buyers - 5);
        assertThat(stockOf(CALOI_EXPLORER)).isZero();
    }

    private static PlaceOrderRequest request(PlaceOrderRequest.Item... items) {
        return new PlaceOrderRequest("Cliente Teste", "cliente@example.com", null, List.of(items));
    }

    private static PlaceOrderRequest.Item item(long productId, int quantity) {
        return new PlaceOrderRequest.Item(productId, quantity);
    }
}
