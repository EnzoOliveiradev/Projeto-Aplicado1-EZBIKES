// Jornada de um cliente da EZBikes: catálogo -> detalhe -> (às vezes) carrinho -> checkout.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS } from './config.js';

// 409 (sem estoque) é uma resposta correta do negócio, não uma falha: não entra em http_req_failed.
// O mesmo critério do SLI de disponibilidade (só 5xx consomem o error budget).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export const checkoutSuccess = new Rate('ezbikes_checkout_success');
export const checkoutDuration = new Trend('ezbikes_checkout_duration', true);

function headersFor(requestId) {
  // X-Request-Id vira o trace_id nos logs: dá para achar no Kibana as requisições de um teste.
  return { ...JSON_HEADERS, 'X-Request-Id': requestId };
}

function requestId() {
  return `k6-${__VU}-${__ITER}-${Date.now()}`;
}

/** Lista o catálogo e devolve um produto aleatório (ou null se o catálogo falhar). */
export function browseCatalog(think = 0) {
  const id = requestId();
  const list = http.get(`${BASE_URL}/api/products`, {
    headers: headersFor(`${id}-list`),
    tags: { name: 'GET /api/products', flow: 'catalog' },
  });
  check(list, { 'catálogo respondeu 200': (r) => r.status === 200 });
  if (list.status !== 200) {
    return null;
  }
  const products = list.json();
  const product = products[Math.floor(Math.random() * products.length)];

  const detail = http.get(`${BASE_URL}/api/products/${product.id}`, {
    headers: headersFor(`${id}-detail`),
    tags: { name: 'GET /api/products/{id}', flow: 'catalog' },
  });
  check(detail, { 'detalhe respondeu 200': (r) => r.status === 200 });
  if (think > 0) {
    sleep(think * Math.random());
  }
  return product;
}

/** Checkout direto (itens no corpo), sem passar pelo carrinho. */
export function checkoutDirect(product) {
  return placeOrder({
    customerName: `Cliente k6 ${__VU}`,
    customerEmail: `k6-vu${__VU}@example.com`,
    items: [{ productId: product.id, quantity: 1 }],
  });
}

/** Adiciona ao carrinho e fecha o pedido a partir dele (fluxo da vitrine). */
export function checkoutViaCart(product) {
  const cartId = `k6-cart-${__VU}-${__ITER}`;
  const add = http.post(`${BASE_URL}/api/carts/${cartId}/items`,
    JSON.stringify({ productId: product.id, quantity: 1 }), {
      headers: headersFor(`${requestId()}-cart`),
      tags: { name: 'POST /api/carts/{cartId}/items', flow: 'cart' },
    });
  check(add, { 'carrinho respondeu 200': (r) => r.status === 200 });
  return placeOrder({
    customerName: `Cliente k6 ${__VU}`,
    customerEmail: `k6-vu${__VU}@example.com`,
    cartId,
  });
}

function placeOrder(body) {
  const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify(body), {
    headers: headersFor(`${requestId()}-order`),
    tags: { name: 'POST /api/orders', flow: 'checkout' },
  });
  check(res, { 'checkout 201 (pago) ou 409 (sem estoque)': (r) => r.status === 201 || r.status === 409 });
  if (res.status !== 409) {
    checkoutSuccess.add(res.status === 201);
  }
  checkoutDuration.add(res.timings.duration);
  return res;
}

/** Jornada completa: navega e, com probabilidade checkoutRatio, compra pelo carrinho. */
export function shopperJourney({ checkoutRatio = 0.3, think = 1 } = {}) {
  const product = browseCatalog(think);
  if (product && Math.random() < checkoutRatio) {
    checkoutViaCart(product);
  }
  sleep(think);
}
