package br.com.ezbikes.payment;

import br.com.ezbikes.chaos.ChaosState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTest {

    private final PaymentProperties properties = new PaymentProperties(0, 0, 1);
    private final ChaosState chaos = new ChaosState(properties);
    private final FakePaymentGateway gateway = new FakePaymentGateway(properties, chaos);

    @Test
    void aprovaQuandoNaoHaFalhaInjetada() {
        assertThatCode(() -> gateway.charge(UUID.randomUUID(), BigDecimal.TEN)).doesNotThrowAnyException();
    }

    @Test
    void falhaQuandoOExperimentoMandaFalharSempre() {
        chaos.update(1.0, 0, 0, ChaosState.LatencyScope.ALL);

        assertThatThrownBy(() -> gateway.charge(UUID.randomUUID(), BigDecimal.TEN))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("gateway_unavailable");
    }

    @Test
    void taxaInicialVemDaConfiguracao() {
        ChaosState fromConfig = new ChaosState(new PaymentProperties(1.0, 0, 1));
        FakePaymentGateway failing = new FakePaymentGateway(properties, fromConfig);

        assertThatThrownBy(() -> failing.charge(UUID.randomUUID(), BigDecimal.TEN))
                .isInstanceOf(PaymentFailedException.class);
    }
}
