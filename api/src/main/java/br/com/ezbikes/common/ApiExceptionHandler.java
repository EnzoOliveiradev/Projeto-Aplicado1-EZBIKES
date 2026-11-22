package br.com.ezbikes.common;

import br.com.ezbikes.order.OutOfStockException;
import br.com.ezbikes.payment.PaymentFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Converte exceções em respostas RFC 7807 (application/problem+json).
 *
 * <p>A classificação do status importa para o SLI de disponibilidade:
 * 4xx são erros do cliente ou regras de negócio (não consomem o error budget);
 * 5xx são falhas do serviço (consomem o error budget).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Recurso não encontrado", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Requisição inválida", ex.getMessage());
    }

    @ExceptionHandler(OutOfStockException.class)
    ProblemDetail outOfStock(OutOfStockException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Estoque insuficiente", ex.getMessage());
        pd.setProperty("sku", ex.getSku());
        pd.setProperty("requested", ex.getRequested());
        pd.setProperty("available", ex.getAvailable());
        return pd;
    }

    @ExceptionHandler(PaymentFailedException.class)
    ProblemDetail paymentFailed(PaymentFailedException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "Falha no pagamento",
                "O gateway de pagamento não respondeu corretamente. Tente novamente.");
        pd.setProperty("reason", ex.getReason());
        if (ex.getOrderId() != null) {
            pd.setProperty("orderId", ex.getOrderId());
        }
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Erro inesperado ao processar requisição", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente mais tarde.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
