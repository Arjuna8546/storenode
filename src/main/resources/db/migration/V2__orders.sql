-- "orders", because ORDER is a reserved word in SQL.
CREATE TABLE orders (
                        id               UUID           PRIMARY KEY,
                        branch_id        UUID           NOT NULL,
                        order_number     VARCHAR(40)    NOT NULL UNIQUE,
                        business_date    DATE           NOT NULL,
                        terminal_id      VARCHAR(50),
                        status           VARCHAR(20)    NOT NULL,
                        currency         CHAR(3)        NOT NULL,
                        subtotal         NUMERIC(19, 4) NOT NULL,
                        tax_total        NUMERIC(19, 4) NOT NULL,
                        total            NUMERIC(19, 4) NOT NULL,
                        payment_method   VARCHAR(10)    NOT NULL,
                        amount_tendered  NUMERIC(19, 4) NOT NULL,
                        change_due       NUMERIC(19, 4) NOT NULL,
                        placed_at        TIMESTAMPTZ    NOT NULL,
                        version          BIGINT         NOT NULL DEFAULT 0,
                        created_at       TIMESTAMPTZ    NOT NULL,
                        updated_at       TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_orders_business_date ON orders (business_date);

-- Product details are copied onto the line: an order keeps showing what was actually sold.
CREATE TABLE order_line (
                            id                    UUID           PRIMARY KEY,
                            order_id              UUID           NOT NULL REFERENCES orders (id),
                            line_no               INTEGER        NOT NULL,
                            product_id            UUID           NOT NULL REFERENCES product (id),
                            sku                   VARCHAR(50)    NOT NULL,
                            name                  VARCHAR(200)   NOT NULL,
                            unit_price            NUMERIC(19, 4) NOT NULL,
                            quantity              INTEGER        NOT NULL CHECK (quantity > 0),
                            tax_rate              NUMERIC(7, 4)  NOT NULL,
                            net_amount            NUMERIC(19, 4) NOT NULL,
                            tax_amount            NUMERIC(19, 4) NOT NULL,
                            line_total            NUMERIC(19, 4) NOT NULL,
                            requires_preparation  BOOLEAN        NOT NULL,
                            UNIQUE (order_id, line_no)
);

CREATE INDEX idx_order_line_order_id ON order_line (order_id);
