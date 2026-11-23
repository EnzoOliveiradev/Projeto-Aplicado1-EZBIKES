// Pico repentino: 0 -> 150 usuários em 10 segundos, mantidos por 1 minuto.
// Objetivo: observar a saturação (pool de conexões do banco, threads do Tomcat) e a recuperação.
//   docker compose run --rm k6 run --summary-export /scripts/results/spike.json /scripts/spike.js
import { replenishStock } from './lib/admin.js';
import { shopperJourney } from './lib/scenario.js';

export const options = {
  scenarios: {
    pico: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 150 },
        { duration: '1m', target: 150 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{flow:catalog}': ['p(99)<1000'],
  },
};

export function setup() {
  replenishStock(100000);
}

export default function () {
  shopperJourney({ checkoutRatio: 0.3, think: 0.3 });
}
