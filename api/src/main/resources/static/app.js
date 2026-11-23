// EZBikes · vitrine. JavaScript puro, sem dependências.
// Regra de segurança: dados vindos da API entram na página só por textContent (nunca innerHTML).
'use strict';

const CATEGORIES = { MTB: 'Mountain bike', SPEED: 'Speed', URBANA: 'Urbana', ELETRICA: 'Elétrica', INFANTIL: 'Infantil' };
const money = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const BIKE_SVG = '<svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="14" cy="42" r="10"/><circle cx="50" cy="42" r="10"/><path d="M14 42l10-18h16l10 18M24 24l8 18h-18M40 24l-4-8h-6M32 42l8-18"/></svg>';

// ------------------------------------------------------------------ utilidades

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  for (const child of children) {
    if (child != null) node.append(child);
  }
  return node;
}

function bikeIcon(className) {
  // SVG estático e confiável (constante acima), não vem da API.
  const wrapper = el('div', { class: className });
  wrapper.innerHTML = BIKE_SVG;
  return wrapper;
}

function storage(action, key, value) {
  try {
    if (action === 'get') return window.localStorage.getItem(key);
    if (action === 'set') window.localStorage.setItem(key, value);
    if (action === 'remove') window.localStorage.removeItem(key);
  } catch (e) {
    return null; // modo privado ou armazenamento bloqueado: a loja continua funcionando na sessão
  }
  return null;
}

let memoryCartId = null;
function cartId() {
  let id = storage('get', 'ezbikes.cartId') || memoryCartId;
  if (!id) {
    id = (crypto.randomUUID ? crypto.randomUUID() : `cart-${Date.now()}-${Math.random().toString(16).slice(2)}`);
    memoryCartId = id;
    storage('set', 'ezbikes.cartId', id);
  }
  return id;
}

function resetCartId() {
  memoryCartId = null;
  storage('remove', 'ezbikes.cartId');
}

/** Chama a API e devolve { ok, status, data, requestId }. O requestId é o trace_id dos logs. */
async function api(method, path, body) {
  const options = { method, headers: { Accept: 'application/json' } };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  try {
    const res = await fetch(path, options);
    let data = null;
    const type = res.headers.get('Content-Type') || '';
    if (type.includes('json')) data = await res.json();
    return { ok: res.ok, status: res.status, data, requestId: res.headers.get('X-Request-Id') };
  } catch (e) {
    return { ok: false, status: 0, data: null, requestId: null };
  }
}

function notice(kind, title, detail, requestId) {
  const box = el('div', { class: `notice notice-${kind}`, role: kind === 'good' ? 'status' : 'alert' },
    el('strong', { text: title }));
  if (detail) box.append(el('span', { text: detail }));
  if (requestId) {
    box.append(el('div', {}, el('small', { text: 'Código de atendimento: ' }), el('code', { text: requestId })));
  }
  return box;
}

function failure(res, fallback) {
  if (res.status === 0) return notice('bad', 'Sem conexão com a loja', 'Verifique se a API está no ar e tente novamente.');
  const detail = res.data && res.data.detail ? res.data.detail : fallback;
  const title = res.data && res.data.title ? res.data.title : 'Algo deu errado';
  return notice(res.status >= 500 ? 'bad' : 'warn', title, detail, res.requestId);
}

async function refreshCartBadge() {
  const badge = document.querySelector('[data-cart-count]');
  if (!badge) return;
  const res = await api('GET', `/api/carts/${cartId()}`);
  const count = res.ok ? res.data.items.reduce((sum, i) => sum + i.quantity, 0) : 0;
  badge.textContent = String(count);
  badge.hidden = count === 0;
}

// ------------------------------------------------------------------ catálogo

async function catalogPage() {
  const grid = document.getElementById('products');
  const status = document.getElementById('status');
  const category = document.getElementById('category');
  const brand = document.getElementById('brand');

  for (const [value, label] of Object.entries(CATEGORIES)) {
    category.append(el('option', { value, text: label }));
  }

  const all = await api('GET', '/api/products');
  if (all.ok) {
    [...new Set(all.data.map((p) => p.brand))].sort().forEach((b) => brand.append(el('option', { value: b, text: b })));
  }

  async function load() {
    const params = new URLSearchParams();
    if (category.value) params.set('category', category.value);
    if (brand.value) params.set('brand', brand.value);
    const res = await api('GET', `/api/products${params.toString() ? `?${params}` : ''}`);
    grid.replaceChildren();
    status.replaceChildren();
    if (!res.ok) {
      status.append(failure(res, 'Não foi possível carregar o catálogo.'));
      return;
    }
    if (res.data.length === 0) {
      grid.append(el('p', { class: 'empty', text: 'Nenhuma bicicleta com esses filtros.' }));
      return;
    }
    for (const p of res.data) {
      grid.append(el('a', { class: 'card', href: `produto.html?id=${encodeURIComponent(p.id)}` },
        bikeIcon('card-media'),
        el('div', { class: 'card-body' },
          el('span', { class: 'card-brand', text: p.brand }),
          el('span', { class: 'card-name', text: p.name }),
          el('span', { class: 'tag', text: CATEGORIES[p.category] || p.category }),
          el('span', { class: 'card-price', text: money.format(p.price) }))));
    }
  }

  category.addEventListener('change', load);
  brand.addEventListener('change', load);
  await load();
}

// ------------------------------------------------------------------ produto

async function productPage() {
  const root = document.getElementById('product');
  const id = new URLSearchParams(location.search).get('id');
  const res = await api('GET', `/api/products/${encodeURIComponent(id || '')}`);
  if (!res.ok) {
    root.replaceChildren(failure(res, 'Produto não encontrado.'), el('a', { href: 'index.html', text: '← Voltar ao catálogo' }));
    return;
  }
  const p = res.data;
  document.title = `${p.name} · EZBikes`;

  const feedback = el('div', { 'aria-live': 'polite' });
  const qty = el('input', { id: 'qty', type: 'number', min: '1', max: String(Math.min(10, Math.max(1, p.stock))), value: '1' });
  const add = el('button', { class: 'btn', type: 'button', text: 'Adicionar ao carrinho' });
  if (p.stock === 0) add.disabled = true;

  add.addEventListener('click', async () => {
    add.disabled = true;
    const result = await api('POST', `/api/carts/${cartId()}/items`, { productId: p.id, quantity: Number(qty.value) });
    feedback.replaceChildren(result.ok
      ? el('div', { class: 'notice notice-good', role: 'status' },
        el('strong', { text: 'Adicionado ao carrinho' }), el('a', { href: 'carrinho.html', text: 'Ir para o carrinho →' }))
      : failure(result, 'Não foi possível adicionar.'));
    add.disabled = p.stock === 0;
    refreshCartBadge();
  });

  root.replaceChildren(el('div', { class: 'product' },
    bikeIcon('product-media'),
    el('div', { class: 'product-info' },
      el('span', { class: 'card-brand', text: p.brand }),
      el('h1', { text: p.name }),
      el('span', { class: 'tag', text: CATEGORIES[p.category] || p.category }),
      el('p', { class: 'muted', text: p.description || '' }),
      el('div', { class: 'price', text: money.format(p.price) }),
      el('div', { class: 'stock', text: p.stock > 0 ? `${p.stock} unidades em estoque` : 'Sem estoque no momento' }),
      el('div', { class: 'buy-row' },
        el('label', { for: 'qty', class: 'form' }, el('span', { text: 'Quantidade' }), qty), add),
      feedback,
      el('a', { href: 'index.html', text: '← Continuar comprando' }))));
}

// ------------------------------------------------------------------ carrinho e checkout

async function cartPage() {
  const list = document.getElementById('cart');
  const status = document.getElementById('status');
  const form = document.getElementById('checkout');
  const submit = form.querySelector('button[type=submit]');

  async function render() {
    const res = await api('GET', `/api/carts/${cartId()}`);
    list.replaceChildren(el('h2', { text: 'Seu carrinho' }));
    if (!res.ok) {
      list.append(failure(res, 'Não foi possível carregar o carrinho.'));
      submit.disabled = true;
      return;
    }
    if (res.data.items.length === 0) {
      list.append(el('p', { class: 'empty' }, 'O carrinho está vazio. ', el('a', { href: 'index.html', text: 'Ver bicicletas' })));
      submit.disabled = true;
      return;
    }
    for (const item of res.data.items) {
      const remove = el('button', { class: 'btn btn-ghost btn-small', type: 'button', text: 'Remover', 'aria-label': `Remover ${item.name}` });
      remove.addEventListener('click', async () => {
        await api('DELETE', `/api/carts/${cartId()}/items/${item.productId}`);
        render();
        refreshCartBadge();
      });
      list.append(el('div', { class: 'cart-line' },
        el('div', {}, el('div', { text: item.name }),
          el('div', { class: 'muted', text: `${item.quantity} × ${money.format(item.unitPrice)}` })),
        el('strong', { text: money.format(item.subtotal) }),
        remove));
    }
    list.append(el('div', { class: 'total-row' }, el('span', { text: 'Total' }), el('span', { text: money.format(res.data.total) })));
    submit.disabled = false;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    submit.textContent = 'Processando pagamento…';
    status.replaceChildren();
    const data = new FormData(form);
    const res = await api('POST', '/api/orders', {
      customerName: String(data.get('name') || '').trim(),
      customerEmail: String(data.get('email') || '').trim(),
      cartId: cartId(),
    });
    submit.textContent = 'Finalizar compra';
    if (res.status === 201) {
      resetCartId();
      location.href = `pedido.html?id=${encodeURIComponent(res.data.id)}`;
      return;
    }
    if (res.status === 502) {
      status.append(notice('bad', 'O pagamento não foi aprovado',
        'O provedor de pagamento não respondeu. Nada foi cobrado e o estoque foi liberado; você pode tentar de novo.',
        res.requestId));
    } else {
      status.append(failure(res, 'Não foi possível concluir o pedido.'));
    }
    submit.disabled = false;
    render();
  });

  await render();
}

// ------------------------------------------------------------------ confirmação do pedido

const STATUS_TEXT = { PAID: 'Pago', PAYMENT_FAILED: 'Pagamento recusado', CREATED: 'Aguardando pagamento' };

async function orderPage() {
  const root = document.getElementById('order');
  const id = new URLSearchParams(location.search).get('id') || '';
  const res = await api('GET', `/api/orders/${encodeURIComponent(id)}`);
  if (!res.ok) {
    root.replaceChildren(failure(res, 'Pedido não encontrado.'));
    return;
  }
  const o = res.data;
  const items = el('div', {});
  for (const item of o.items) {
    items.append(el('div', { class: 'cart-line' },
      el('div', {}, el('div', { text: item.sku }),
        el('div', { class: 'muted', text: `${item.quantity} × ${money.format(item.unitPrice)}` })),
      el('strong', { text: money.format(item.subtotal) }), el('span')));
  }
  root.replaceChildren(...[
    o.status === 'PAID' ? notice('good', 'Compra confirmada!', `Obrigado, ${o.customerName}. Seu pedido foi pago.`) : null,
    el('div', { class: 'panel' },
      el('h2', { text: 'Detalhes do pedido' }),
      el('dl', { class: 'facts' },
        el('dt', { text: 'Pedido' }), el('dd', {}, el('code', { text: o.id })),
        el('dt', { text: 'Situação' }), el('dd', {}, el('span', { class: `status status-${o.status}`, text: STATUS_TEXT[o.status] || o.status })),
        el('dt', { text: 'Data' }), el('dd', { text: new Date(o.createdAt).toLocaleString('pt-BR') })),
      items,
      el('div', { class: 'total-row' }, el('span', { text: 'Total' }), el('span', { text: money.format(o.total) }))),
    el('p', { class: 'muted' }, 'Para a demonstração: no Kibana, busque ', el('code', { text: `order_id: "${o.id}"` }),
      ' para ver toda a história deste pedido.'),
    el('a', { class: 'btn', href: 'index.html', text: 'Continuar comprando' }),
  ].filter(Boolean));
}

// ------------------------------------------------------------------ inicialização

const PAGES = { catalog: catalogPage, product: productPage, cart: cartPage, order: orderPage };
document.addEventListener('DOMContentLoaded', () => {
  const page = PAGES[document.body.dataset.page];
  if (page) page();
  refreshCartBadge();
});
