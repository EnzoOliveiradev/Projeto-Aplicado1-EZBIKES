// Chamadas à API administrativa (/admin/**), usadas em setup() e teardown().
import http from 'k6/http';
import { fail } from 'k6';
import { ADMIN_TOKEN, BASE_URL, JSON_HEADERS } from './config.js';

function adminRequest(method, path, body) {
  if (!ADMIN_TOKEN) {
    fail('ADMIN_TOKEN não informado: a API /admin/** exige o cabeçalho X-Admin-Token');
  }
  const res = http.request(method, `${BASE_URL}${path}`, body ? JSON.stringify(body) : null, {
    headers: { ...JSON_HEADERS, 'X-Admin-Token': ADMIN_TOKEN },
    tags: { name: `${method} ${path}`, flow: 'admin' },
  });
  if (res.status !== 200) {
    fail(`${method} ${path} retornou ${res.status}: ${res.body}`);
  }
  return res.json();
}

/** Repõe o estoque de todos os produtos, para o teste não terminar em "sem estoque". */
export function replenishStock(quantity) {
  return adminRequest('POST', '/admin/stock/replenish', { quantity });
}

export function setChaos(config) {
  return adminRequest('PUT', '/admin/chaos', config);
}

export function resetChaos() {
  return adminRequest('DELETE', '/admin/chaos');
}
