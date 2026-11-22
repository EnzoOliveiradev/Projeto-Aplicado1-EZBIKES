package br.com.ezbikes.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @Test
    void mantemPrimeiraLetraEDominio() {
        assertThat(PiiMasker.maskEmail("ana.souza@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void removeEspacosAntesDeMascarar() {
        assertThat(PiiMasker.maskEmail("  bruno@ezbikes.com.br ")).isEqualTo("b***@ezbikes.com.br");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sem-arroba", "@dominio.com", "usuario@"})
    void valorQueNaoPareceEmailEMascaradoPorCompleto(String value) {
        assertThat(PiiMasker.maskEmail(value)).isEqualTo("***");
    }

    @Test
    void nuloEVazioPermanecemComoEstao() {
        assertThat(PiiMasker.maskEmail(null)).isNull();
        assertThat(PiiMasker.maskEmail("")).isEmpty();
    }
}
