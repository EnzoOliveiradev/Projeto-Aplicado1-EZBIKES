package br.com.ezbikes.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static net.logstash.logback.argument.StructuredArguments.kv;

/** Exige o cabeçalho X-Admin-Token correto em /admin/** (comparação em tempo constante). */
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";
    private static final Logger log = LoggerFactory.getLogger(AdminTokenInterceptor.class);

    private final byte[] expected;

    public AdminTokenInterceptor(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String received = request.getHeader(HEADER);
        if (received != null && MessageDigest.isEqual(expected, received.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        log.warn("Acesso negado à API administrativa", kv("event.action", "admin.unauthorized"));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Não autorizado\",\"status\":401,\"detail\":\"Informe o cabeçalho X-Admin-Token válido\"}");
        return false;
    }
}
