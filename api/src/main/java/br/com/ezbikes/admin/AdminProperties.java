package br.com.ezbikes.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API administrativa (/admin/**) usada nos experimentos: injeção de falhas e reposição de estoque.
 *
 * @param enabled liga a API; desligada por padrão, ligada só no compose de demonstração
 * @param token   segredo exigido no cabeçalho {@code X-Admin-Token}
 */
@ConfigurationProperties("ezbikes.admin")
public record AdminProperties(boolean enabled, String token) {

    public AdminProperties {
        if (enabled && (token == null || token.length() < 12)) {
            throw new IllegalArgumentException(
                    "ezbikes.admin.token (EZBIKES_ADMIN_TOKEN) deve ter ao menos 12 caracteres quando a API admin está ligada");
        }
    }
}
