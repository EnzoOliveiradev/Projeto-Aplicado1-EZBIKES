package br.com.ezbikes.admin;

import br.com.ezbikes.catalog.ProductRepository;
import br.com.ezbikes.chaos.ChaosState;
import br.com.ezbikes.chaos.ChaosState.LatencyScope;
import br.com.ezbikes.chaos.ChaosState.Snapshot;
import br.com.ezbikes.common.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * API administrativa para os experimentos de confiabilidade. Só existe quando
 * {@code ezbikes.admin.enabled=true} e exige o cabeçalho {@code X-Admin-Token}.
 *
 * <pre>
 * GET    /admin/chaos                 estado atual das falhas injetadas
 * PUT    /admin/chaos                 {"paymentFailureRate":0.05,"errorRate":0,"extraLatencyMs":0,"latencyScope":"ALL"}
 * DELETE /admin/chaos                 desliga todas as falhas
 * POST   /admin/stock/replenish       {"quantity":1000} repõe o estoque de todos os produtos
 * </pre>
 */
@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "ezbikes.admin.enabled", havingValue = "true")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ChaosState chaos;
    private final ProductRepository products;

    public AdminController(ChaosState chaos, ProductRepository products) {
        this.chaos = chaos;
        this.products = products;
    }

    public record ChaosRequest(Double paymentFailureRate, Double errorRate, Integer extraLatencyMs,
                               LatencyScope latencyScope) {
    }

    public record ReplenishRequest(@NotNull @Min(0) @Max(1_000_000) Integer quantity) {
    }

    @GetMapping("/chaos")
    public Snapshot getChaos() {
        return chaos.snapshot();
    }

    @PutMapping("/chaos")
    public Snapshot setChaos(@RequestBody ChaosRequest request) {
        try {
            Snapshot updated = chaos.update(
                    orZero(request.paymentFailureRate()), orZero(request.errorRate()),
                    request.extraLatencyMs() == null ? 0 : request.extraLatencyMs(), request.latencyScope());
            log.warn("Experimento de caos atualizado",
                    kv("event.action", "chaos.updated"),
                    kv("chaos_payment_failure_rate", updated.paymentFailureRate()),
                    kv("chaos_error_rate", updated.errorRate()),
                    kv("chaos_extra_latency_ms", updated.extraLatencyMs()),
                    kv("chaos_latency_scope", updated.latencyScope()));
            return updated;
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(e.getMessage());
        }
    }

    @DeleteMapping("/chaos")
    public Snapshot resetChaos() {
        log.info("Experimento de caos encerrado", kv("event.action", "chaos.reset"));
        return chaos.reset();
    }

    @PostMapping("/stock/replenish")
    @Transactional
    public Map<String, Integer> replenish(@Valid @RequestBody ReplenishRequest request) {
        int updated = products.setAllStock(request.quantity());
        log.info("Estoque reposto", kv("event.action", "stock.replenished"), kv("stock_quantity", request.quantity()));
        return Map.of("products", updated, "quantity", request.quantity());
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
