package br.com.ezbikes.order;

import br.com.ezbikes.IntegrationTest;
import br.com.ezbikes.payment.PaymentFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica o mapeamento de status HTTP, que define o que consome o error budget:
 * 4xx (cliente/negócio) não consome; 5xx (serviço) consome.
 */
class OrderApiIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pedidoValidoRetorna201ComLocation() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order(1, 1)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.customerEmail").doesNotExist());
    }

    @Test
    void dadosInvalidosRetornam400() throws Exception {
        String body = """
                {"customerName": "", "customerEmail": "nao-e-email", "items": [{"productId": 1, "quantity": 0}]}
                """;
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonMalformadoRetorna400() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void estoqueInsuficienteRetorna409() throws Exception {
        setStock(1, 0);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order(1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estoque insuficiente"))
                .andExpect(jsonPath("$.sku").value("MTB-CAL-EXP"));
    }

    @Test
    void falhaNoGatewayRetorna502ComIdDoPedido() throws Exception {
        doThrow(new PaymentFailedException("gateway_unavailable")).when(paymentGateway).charge(any(), any());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order(1, 1)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("gateway_unavailable"))
                .andExpect(jsonPath("$.orderId", notNullValue()));
    }

    @Test
    void pedidoInexistenteRetorna404() throws Exception {
        mvc.perform(get("/api/orders/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cartIdInvalidoRetorna400() throws Exception {
        mvc.perform(get("/api/carts/<script>"))
                .andExpect(status().isBadRequest());
    }

    private static String order(long productId, int quantity) {
        return """
                {"customerName": "Ana", "customerEmail": "ana@example.com",
                 "items": [{"productId": %d, "quantity": %d}]}
                """.formatted(productId, quantity);
    }
}
