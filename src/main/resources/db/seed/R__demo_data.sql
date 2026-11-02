-- Demo menu for the dev profile only. Safe to run again: rows that already exist are left alone.

INSERT INTO product (id, branch_id, sku, name, unit_price, currency, tax_rate, requires_preparation, created_at, updated_at)
VALUES
    ('0190f3c0-0000-7000-8000-000000000101', '0190f3c0-0000-7000-8000-000000000001', 'MEAL-BOX', 'Fresh meal box',        249.0000, 'INR', 0.0500, FALSE, now(), now()),
    ('0190f3c0-0000-7000-8000-000000000102', '0190f3c0-0000-7000-8000-000000000001', 'WRAP-CHK', 'Grilled chicken wrap',  189.0000, 'INR', 0.0500, TRUE,  now(), now()),
    ('0190f3c0-0000-7000-8000-000000000103', '0190f3c0-0000-7000-8000-000000000001', 'LATTE-M',  'Latte (medium)',        160.0000, 'INR', 0.1800, TRUE,  now(), now()),
    ('0190f3c0-0000-7000-8000-000000000104', '0190f3c0-0000-7000-8000-000000000001', 'WATER-1L', 'Mineral water 1L',       40.0000, 'INR', 0.1200, FALSE, now(), now())
    ON CONFLICT (id) DO NOTHING;

INSERT INTO inventory (product_id, branch_id, quantity, updated_at)
VALUES
    ('0190f3c0-0000-7000-8000-000000000101', '0190f3c0-0000-7000-8000-000000000001', 10,  now()),
    ('0190f3c0-0000-7000-8000-000000000102', '0190f3c0-0000-7000-8000-000000000001', 50,  now()),
    ('0190f3c0-0000-7000-8000-000000000103', '0190f3c0-0000-7000-8000-000000000001', 200, now()),
    ('0190f3c0-0000-7000-8000-000000000104', '0190f3c0-0000-7000-8000-000000000001', 100, now())
    ON CONFLICT (product_id) DO NOTHING;
