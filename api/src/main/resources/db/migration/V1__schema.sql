-- EZBikes: esquema inicial (catálogo, pedidos e itens)

CREATE TABLE products (
    id          BIGSERIAL      PRIMARY KEY,
    sku         VARCHAR(32)    NOT NULL UNIQUE,
    name        VARCHAR(120)   NOT NULL,
    brand       VARCHAR(60)    NOT NULL,
    category    VARCHAR(20)    NOT NULL,
    price       NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    stock       INTEGER        NOT NULL CHECK (stock >= 0),
    description VARCHAR(500),
    version     BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_brand ON products (lower(brand));

CREATE TABLE orders (
    id             UUID           PRIMARY KEY,
    customer_name  VARCHAR(120)   NOT NULL,
    customer_email VARCHAR(160)   NOT NULL,
    status         VARCHAR(30)    NOT NULL,
    total          NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at);

CREATE TABLE order_items (
    id         BIGSERIAL      PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id BIGINT         NOT NULL REFERENCES products (id),
    sku        VARCHAR(32)    NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price > 0),
    quantity   INTEGER        NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
