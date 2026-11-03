-- One row per branch per business day: the last order number handed out.
-- Taking the next number means updating this row, and the row stays locked until the order commits.
-- If the order fails and rolls back, the counter goes back too, so no number is ever skipped.
CREATE TABLE fiscal_counter (
                                branch_code    VARCHAR(10) NOT NULL,
                                business_date  DATE        NOT NULL,
                                last_number    INTEGER     NOT NULL CHECK (last_number >= 0),
                                PRIMARY KEY (branch_code, business_date)
);

-- Orders that already exist keep their numbers, so carry on counting after the highest one.
INSERT INTO fiscal_counter (branch_code, business_date, last_number)
SELECT split_part(order_number, '-', 2), business_date, MAX(split_part(order_number, '-', 4)::INTEGER)
FROM orders
GROUP BY split_part(order_number, '-', 2), business_date;
