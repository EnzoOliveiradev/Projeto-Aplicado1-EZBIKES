package br.com.ezbikes.observability;

/**
 * Mascaramento de dados pessoais antes de irem para os logs (LGPD: minimização de dados).
 * O e-mail completo fica só no banco; nos logs aparece o suficiente para suporte, como
 * {@code a***@example.com}.
 */
public final class PiiMasker {

    private static final String MASK = "***";

    private PiiMasker() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        String trimmed = email.strip();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            // Não parece um e-mail: não arrisca expor nada.
            return MASK;
        }
        return trimmed.charAt(0) + MASK + trimmed.substring(at);
    }
}
