// Teste de estresse: taxa de chegada crescente até a loja violar o SLO de latência do catálogo.
// Objetivo: descobrir o ponto de ruptura (o load.js e o spike.js não chegaram a degradar).
//   docker compose run --rm k6 run --summary-export /scripts/results/stress.json /scripts/stress.js
//
// Cada iteração faz 2 requisições ao catálogo e, em 30% delas, carrinho + checkout (~2,6 req).
// O teste é interrompido automaticamente quando o p99 do catálogo passa de 300 ms ou os erros
// passam de 1%. A taxa alcançada nesse momento é o limite desta máquina.
//
// Atenção: k6 e loja rodam no mesmo computador e disputam CPU; o limite medido é do conjunto.
import { replenishStock } from './lib/admin.js';
import { shopperJourney } from './lib/scenario.js';

export const options = {
  scenarios: {
    estresse: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { duration: '1m', target: 300 },
        { duration: '1m', target: 600 },
        { duration: '1m', target: 900 },
        { duration: '1m', target: 1200 },
        { duration: '1m', target: 1500 },
        { duration: '1m', target: 2000 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{flow:catalog}': [{ threshold: 'p(99)<300', abortOnFail: true, delayAbortEval: '20s' }],
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '20s' }],
  },
};

export function setup() {
  replenishStock(1000000);
}

export default function () {
  shopperJourney({ checkoutRatio: 0.3, think: 0 });
}
