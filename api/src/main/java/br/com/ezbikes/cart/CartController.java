package br.com.ezbikes.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts/{cartId}")
public class CartController {

    /** cartId gerado pelo navegador (UUID); o formato restrito evita lixo na chave do mapa. */
    static final String CART_ID_PATTERN = "^[A-Za-z0-9-]{8,64}$";

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    public record AddItemRequest(@NotNull @Positive Long productId,
                                 @NotNull @Min(1) @Max(Cart.MAX_QUANTITY_PER_ITEM) Integer quantity) {
    }

    @GetMapping
    public CartResponse get(@PathVariable @Pattern(regexp = CART_ID_PATTERN) String cartId) {
        return carts.get(cartId);
    }

    @PostMapping("/items")
    public CartResponse addItem(@PathVariable @Pattern(regexp = CART_ID_PATTERN) String cartId,
                                @Valid @RequestBody AddItemRequest request) {
        return carts.addItem(cartId, request.productId(), request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@PathVariable @Pattern(regexp = CART_ID_PATTERN) String cartId,
                                   @PathVariable long productId) {
        return carts.removeItem(cartId, productId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable @Pattern(regexp = CART_ID_PATTERN) String cartId) {
        carts.clear(cartId);
        return ResponseEntity.noContent().build();
    }
}
