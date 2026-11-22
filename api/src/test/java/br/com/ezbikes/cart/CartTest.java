package br.com.ezbikes.cart;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    private final Instant now = Instant.parse("2026-11-22T12:00:00Z");

    @Test
    void somaQuantidadesDoMesmoProduto() {
        Cart cart = new Cart(now);
        cart.add(1, 2, now);
        cart.add(1, 3, now);

        assertThat(cart.snapshot()).containsEntry(1L, 5);
    }

    @Test
    void limitaUnidadesPorProduto() {
        Cart cart = new Cart(now);
        cart.add(1, Cart.MAX_QUANTITY_PER_ITEM, now);

        assertThatThrownBy(() -> cart.add(1, 1, now)).isInstanceOf(IllegalStateException.class);
        assertThat(cart.snapshot()).containsEntry(1L, Cart.MAX_QUANTITY_PER_ITEM);
    }

    @Test
    void limitaProdutosDiferentes() {
        Cart cart = new Cart(now);
        for (long id = 1; id <= Cart.MAX_DISTINCT_ITEMS; id++) {
            cart.add(id, 1, now);
        }

        assertThatThrownBy(() -> cart.add(999, 1, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removerAtualizaDataDeUso() {
        Cart cart = new Cart(now);
        cart.add(1, 1, now);
        Instant later = now.plusSeconds(60);

        cart.remove(1, later);

        assertThat(cart.snapshot()).isEmpty();
        assertThat(cart.updatedAt()).isEqualTo(later);
    }
}
