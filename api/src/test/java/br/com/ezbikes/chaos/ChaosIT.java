package br.com.ezbikes.chaos;

import br.com.ezbikes.IntegrationTest;
import br.com.ezbikes.admin.AdminTokenInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API administrativa (token) e falhas injetadas entrando nas métricas como falhas reais. */
class ChaosIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Test
    void apiAdminExigeTokenValido() throws Exception {
        mvc.perform(get("/admin/chaos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/chaos").header(AdminTokenInterceptor.HEADER, "token-errado-000"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/chaos").header(AdminTokenInterceptor.HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentFailureRate").value(0.0));
    }

    @Test
    void configuraEReseteaOExperimento() throws Exception {
        mvc.perform(put("/admin/chaos").header(AdminTokenInterceptor.HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentFailureRate\": 0.05, \"extraLatencyMs\": 200, \"latencyScope\": \"CATALOG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentFailureRate").value(0.05))
                .andExpect(jsonPath("$.errorRate").value(0.0))
                .andExpect(jsonPath("$.latencyScope").value("CATALOG"));
        assertThat(chaos.paymentFailureRate()).isEqualTo(0.05);

        mvc.perform(delete("/admin/chaos").header(AdminTokenInterceptor.HEADER, ADMIN_TOKEN))
                .andExpect(status().isOk());
        assertThat(chaos.snapshot().active()).isFalse();
    }

    @Test
    void rejeitaValoresForaDoIntervalo() throws Exception {
        mvc.perform(put("/admin/chaos").header(AdminTokenInterceptor.HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorRate\": 1.5}"))
                .andExpect(status().isBadRequest());
        assertThat(chaos.snapshot().active()).isFalse();
    }

    @Test
    void erroInjetadoRetorna503ERegistraARotaCorreta() throws Exception {
        long before = errors503("/api/products/{id}");
        chaos.update(0, 1.0, 0, ChaosState.LatencyScope.ALL);

        mvc.perform(get("/api/products/1")).andExpect(status().isServiceUnavailable());

        // A URI vem do template da rota: a falha injetada entra no SLI como uma falha real.
        assertThat(errors503("/api/products/{id}")).isEqualTo(before + 1);
    }

    @Test
    void latenciaInjetadaRespeitaOEscopo() throws Exception {
        chaos.update(0, 0, 300, ChaosState.LatencyScope.CATALOG);

        long start = System.nanoTime();
        mvc.perform(get("/api/products")).andExpect(status().isOk());
        long catalogMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        mvc.perform(get("/api/carts/escopo-teste-0001")).andExpect(status().isOk());
        long cartMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(catalogMs).isGreaterThanOrEqualTo(300);
        assertThat(cartMs).isLessThan(300);
    }

    @Test
    void reposicaoDeEstoque() throws Exception {
        mvc.perform(post("/admin/stock/replenish").header(AdminTokenInterceptor.HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(20));
        assertThat(stockOf(1)).isEqualTo(1000);
    }

    private long errors503(String uri) {
        var timer = Search.in(registry).name("http.server.requests").tag("uri", uri).tag("status", "503").timer();
        return timer == null ? 0 : timer.count();
    }
}
