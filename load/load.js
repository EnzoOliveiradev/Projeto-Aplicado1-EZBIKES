// Teste de carga em degraus: 10 -> 50 -> 100 usuários simultâneos (~10 minutos).
// Objetivo: encontrar o ponto em que latência ou erros começam a degradar (passo 13 da aula).
//   docker compose run --rm k6 run --summary-export /scripts/results/load.json /scripts/load.js
//
// Os thresholds espelham os SLOs, mas não abortam o teste: queremos ver até onde a loja aguenta.
import { replenishStock } from './lib/admin.js';
import { shopperJourney } from './lib/scenario.js';

export const options = {
  scenarios: {
    degraus: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 10 },
        { duration: '2m', target: 10 },
        { duration: '1m', target: 50 },
        { duration: '2m', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '2m', target: 100 },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'], //                          SLO de disponibilidade (99,9%)
    'http_req_duration{flow:catalog}': ['p(99)<300'], //         SLO de latência do catálogo
    'http_req_duration{flow:checkout}': ['p(95)<1000'],
    ezbikes_checkout_success: ['rate>0.999'],
  },
};

export function setup() {
  replenishStock(100000);
}

export default function () {
  shopperJourney({ checkoutRatio: 0.3, think: 1 });
}
