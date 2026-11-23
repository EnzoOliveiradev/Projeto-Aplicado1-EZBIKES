package br.com.ezbikes.observability;

import br.com.ezbikes.IntegrationTest;
import br.com.ezbikes.payment.PaymentFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Verifica o contrato dos logs estruturados: JSON por linha, trace_id em todos os logs da
 * requisição, eventos de negócio com order_id e nenhum e-mail em texto claro.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingIT extends IntegrationTest {

    private static final String EMAIL = "maria.oliveira@example.com";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void geraTraceIdQuandoNaoRecebe() throws Exception {
        mvc.perform(get("/api/products/1"))
                .andExpect(header().string(CorrelationFilter.REQUEST_ID_HEADER, matchesPattern("^[a-f0-9]{32}$")));
    }

    @Test
    void reaproveitaTraceIdValidoERejeitaInvalido() throws Exception {
        mvc.perform(get("/api/products/1").header(CorrelationFilter.REQUEST_ID_HEADER, "req-abc-12345"))
                .andExpect(header().string(CorrelationFilter.REQUEST_ID_HEADER, "req-abc-12345"));

        mvc.perform(get("/api/products/1").header(CorrelationFilter.REQUEST_ID_HEADER, "x\"}{injecao"))
                .andExpect(header().string(CorrelationFilter.REQUEST_ID_HEADER, matchesPattern("^[a-f0-9]{32}$")));
    }

    @Test
    void checkoutRegistraEventosCorrelacionadosSemExporEmail(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders")
                        .header(CorrelationFilter.REQUEST_ID_HEADER, "checkout-test-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody()))
                .andReturn();
        String orderId = json.readTree(result.getResponse().getContentAsString()).get("id").asText();

        List<JsonNode> logs = logsOfTrace(output, "checkout-test-0001");

        assertThat(logs).extracting(l -> l.path("event.action").asText())
                .contains("order.created", "order.paid", "http.access");
        assertThat(logs).filteredOn(l -> l.path("event.action").asText().startsWith("order."))
                .allSatisfy(l -> assertThat(l.path("order_id").asText()).isEqualTo(orderId));

        JsonNode created = byAction(logs, "order.created");
        assertThat(created.path("customer_email").asText()).isEqualTo("m***@example.com");
        assertThat(created.path("service").asText()).isEqualTo("ezbikes-api");

        JsonNode access = byAction(logs, "http.access");
        assertThat(access.path("http.route").asText()).isEqualTo("/api/orders");
        assertThat(access.path("http.status").isInt()).isTrue();
        assertThat(access.path("http.status").asInt()).isEqualTo(201);
        assertThat(access.path("http.duration_ms").isNumber()).isTrue();

        assertThat(output.getOut()).doesNotContain(EMAIL);
    }

    @Test
    void falhaNoPagamentoGeraLogDeErroComMotivo(CapturedOutput output) throws Exception {
        doThrow(new PaymentFailedException("gateway_unavailable")).when(paymentGateway).charge(any(), any());

        mvc.perform(post("/api/orders")
                .header(CorrelationFilter.REQUEST_ID_HEADER, "checkout-fail-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(orderBody()));

        List<JsonNode> logs = logsOfTrace(output, "checkout-fail-0001");
        JsonNode failed = byAction(logs, "order.payment_failed");
        assertThat(failed.path("level").asText()).isEqualTo("ERROR");
        assertThat(failed.path("payment_reason").asText()).isEqualTo("gateway_unavailable");

        JsonNode access = byAction(logs, "http.access");
        assertThat(access.path("http.status").asInt()).isEqualTo(502);
        assertThat(access.path("level").asText()).isEqualTo("WARN");
    }

    private List<JsonNode> logsOfTrace(CapturedOutput output, String traceId) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(this::parse)
                .filter(node -> traceId.equals(node.path("trace_id").asText()))
                .toList();
    }

    private static JsonNode byAction(List<JsonNode> logs, String action) {
        return logs.stream()
                .filter(l -> action.equals(l.path("event.action").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Log com event.action=" + action + " não encontrado"));
    }

    private JsonNode parse(String line) {
        try {
            return json.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("Linha de log não é JSON válido: " + line, e);
        }
    }

    private static String orderBody() {
        return """
                {"customerName": "Maria", "customerEmail": "%s",
                 "items": [{"productId": 3, "quantity": 1}]}
                """.formatted(EMAIL);
    }
}
