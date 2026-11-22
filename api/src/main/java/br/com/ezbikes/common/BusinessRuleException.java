package br.com.ezbikes.common;

/**
 * Requisição bem formada que viola uma regra de negócio (HTTP 400).
 * Erro do cliente: não consome o error budget.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
