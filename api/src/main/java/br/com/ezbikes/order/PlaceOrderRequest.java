package br.com.ezbikes.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Pedido de checkout. Os itens vêm do carrinho ({@code cartId}) ou diretamente da lista
 * {@code items} (útil para a API e para o k6). Pelo menos um dos dois deve ser informado.
 */
public record PlaceOrderRequest(
        @NotBlank @Size(max = 120) String customerName,
        @NotBlank @Email @Size(max = 160) String customerEmail,
        @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String cartId,
        @Size(max = 20) List<@Valid @NotNull Item> items) {

    public record Item(@NotNull @Positive Long productId,
                       @NotNull @Min(1) @Max(10) Integer quantity) {
    }
}
