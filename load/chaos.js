// Experimento de caos controlado: tráfego constante + falha injetada pela API /admin/chaos.
// Objetivo: consumir o error budget de forma acelerada e validar que o alerta de burn rate dispara.
//
//   EXPERIMENT=payment  (padrão) gateway de pagamento falha em RATE (0.05) dos checkouts -> 502
//   EXPERIMENT=latency  +LATENCY_MS (400) de atraso no catálogo                          -> SLO de latência
//   EXPERIMENT=errors   RATE das requisições a /api/** falham com 503                    -> todas as rotas
//
//   docker compose run --rm k6 run --summary-export /scripts/results/chaos.json /scripts/chaos.js
//   docker compose run --rm -e EXPERIMENT=latency k6 run /scripts/chaos.js
//
// O teardown() desliga a falha ao final (também se o teste for interrompido com Ctrl+C).
import { replenishStock, resetChaos, setChaos } from './lib/admin.js';
import { browseCatalog, checkoutDirect } from './lib/scenario.js';

const EXPERIMENT = __ENV.EXPERIMENT || 'payment';
const RATE = parseFloat(__ENV.RATE || '0.05');
const LATENCY_MS = parseInt(__ENV.LATENCY_MS || '400', 10);
const DURATION = __ENV.DURATION || '10m';
const ITERATIONS_PER_SECOND = parseInt(__ENV.IPS || '8', 10);

const EXPERIMENTS = {
  payment: { paymentFailureRate: RATE },
  latency: { extraLatencyMs: LATENCY_MS, latencyScope: 'CATALOG' },
  errors: { errorRate: RATE },
};

if (!EXPERIMENTS[EXPERIMENT]) {
  throw new Error(`EXPERIMENT inválido: ${EXPERIMENT} (use payment, latency ou errors)`);
}

export const options = {
  scenarios: {
    trafego_constante: {
      // Taxa de chegada fixa: a carga não diminui quando a API fica lenta (como clientes reais).
      executor: 'constant-arrival-rate',
      rate: ITERATIONS_PER_SECOND,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  // Sem thresholds que abortem: falhas são o objetivo do experimento.
};

export function setup() {
  replenishStock(100000);
  const state = setChaos(EXPERIMENTS[EXPERIMENT]);
  console.log(`Experimento "${EXPERIMENT}" ativo: ${JSON.stringify(state)}`);
}

export default function () {
  const product = browseCatalog(0);
  if (product && EXPERIMENT !== 'latency') {
    checkoutDirect(product);
  }
}

export function teardown() {
  resetChaos();
  console.log('Experimento encerrado: falhas desligadas.');
}
