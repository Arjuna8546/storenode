-- Every table that Central HQ will receive later carries branch_id, version and timestamps,
-- and uses a UUID primary key that the application generates itself.

CREATE TABLE product (
                         id                    UUID           PRIMARY KEY,
                         branch_id             UUID           NOT NULL,
                         sku                   VARCHAR(50)    NOT NULL UNIQUE,
                         name                  VARCHAR(200)   NOT NULL,
                         unit_price            NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
                         currency              CHAR(3)        NOT NULL,
                         tax_rate              NUMERIC(7, 4)  NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 1),
                         requires_preparation  BOOLEAN        NOT NULL DEFAULT FALSE,
                         version               BIGINT         NOT NULL DEFAULT 0,
                         created_at            TIMESTAMPTZ    NOT NULL,
                         updated_at            TIMESTAMPTZ    NOT NULL
);


CREATE TABLE inventory (
                           product_id  UUID        PRIMARY KEY REFERENCES product (id),
                           branch_id   UUID        NOT NULL,
                           quantity    INTEGER     NOT NULL CHECK (quantity >= 0),
                           version     BIGINT      NOT NULL DEFAULT 0,
                           updated_at  TIMESTAMPTZ NOT NULL
);
