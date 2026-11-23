package br.com.ezbikes.chaos;

import br.com.ezbikes.chaos.ChaosState.LatencyScope;
import br.com.ezbikes.chaos.ChaosState.Snapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ThreadLocalRandom;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Aplica latência e erros injetados às rotas /api/**.
 *
 * <p>É um interceptor do Spring MVC (e não um filtro de servlet) de propósito: ele roda depois
 * do mapeamento da rota, então as métricas registram a URI correta ({@code /api/products/{id}})
 * e as falhas injetadas entram nos SLIs como entraria uma falha real.
 */
@Component
public class ChaosInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChaosInterceptor.class);

    private final ChaosState chaos;

    public ChaosInterceptor(ChaosState chaos) {
        this.chaos = chaos;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Snapshot state = chaos.snapshot();
        if (!state.active()) {
            return true;
        }
        if (state.extraLatencyMs() > 0 && inScope(state.latencyScope(), request)) {
            sleep(state.extraLatencyMs());
        }
        if (state.errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < state.errorRate()) {
            log.warn("Falha injetada pelo experimento de caos",
                    kv("event.action", "chaos.injected_error"),
                    kv("chaos_error_rate", state.errorRate()));
            throw new ChaosInjectedException();
        }
        return true;
    }

    static boolean inScope(LatencyScope scope, HttpServletRequest request) {
        String path = request.getRequestURI();
        return switch (scope) {
            case ALL -> true;
            case CATALOG -> "GET".equals(request.getMethod()) && path.startsWith("/api/products");
            case CHECKOUT -> "POST".equals(request.getMethod()) && path.equals("/api/orders");
        };
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
