// Smoke test: poucos usuários por 1 minuto. Verifica se a loja funciona antes dos testes pesados.
//   docker compose run --rm k6 run --summary-export /scripts/results/smoke.json /scripts/smoke.js
import { replenishStock } from './lib/admin.js';
import { shopperJourney } from './lib/scenario.js';

export const options = {
  vus: 2,
  duration: '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{flow:catalog}': ['p(95)<300'],
    'http_req_duration{flow:checkout}': ['p(95)<1000'],
  },
};

export function setup() {
  replenishStock(1000);
}

export default function () {
  shopperJourney({ checkoutRatio: 0.5, think: 1 });
}
