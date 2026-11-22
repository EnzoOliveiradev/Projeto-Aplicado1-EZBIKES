package br.com.ezbikes.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Correlação e log de acesso.
 *
 * <ul>
 *   <li>Lê o {@code X-Request-Id} recebido (ou gera um) e o coloca no MDC como {@code trace_id}:
 *       todos os logs da requisição carregam o mesmo identificador e podem ser filtrados no Kibana.</li>
 *   <li>Devolve o identificador no cabeçalho da resposta, para o cliente informar ao suporte.</li>
 *   <li>Registra <b>um</b> log de acesso por requisição, com status e duração numéricos.</li>
 * </ul>
 *
 * <p>O {@code trace_id} prepara a correlação com tracing distribuído (OpenTelemetry), que fica
 * como evolução do projeto.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID = "trace_id";

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Logger accessLog = LoggerFactory.getLogger("br.com.ezbikes.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(REQUEST_ID_HEADER));
        long start = System.nanoTime();

        MDC.put(TRACE_ID, traceId);
        MDC.put("http.method", request.getMethod());
        MDC.put("http.path", request.getRequestURI());
        response.setHeader(REQUEST_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!isInfrastructure(request)) {
                logAccess(request, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
            MDC.clear();
        }
    }

    private static void logAccess(HttpServletRequest request, int status, long durationMs) {
        Object[] fields = {
                kv("event.action", "http.access"),
                kv("http.status", status),
                kv("http.duration_ms", durationMs)
        };
        String message = "HTTP " + request.getMethod() + " " + request.getRequestURI() + " -> " + status;
        if (status >= 500) {
            accessLog.warn(message, fields);
        } else {
            accessLog.info(message, fields);
        }
    }

    /** Health checks e scraping do Prometheus geram ruído; não entram no log de acesso. */
    private static boolean isInfrastructure(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    static String resolveTraceId(String received) {
        if (received != null && VALID_ID.matcher(received).matches()) {
            return received;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
