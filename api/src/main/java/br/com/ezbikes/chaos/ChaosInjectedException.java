package br.com.ezbikes.chaos;

/** Falha injetada pelo experimento de caos (HTTP 503). Conta como falha do serviço no SLI. */
public class ChaosInjectedException extends RuntimeException {

    public ChaosInjectedException() {
        super("Falha injetada pelo experimento de caos");
    }
}
