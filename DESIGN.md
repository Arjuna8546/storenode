# Design notes

store-node is the ordering, inventory and kitchen-dispatch engine for one branch. Java 21, Spring Boot 3.5, PostgreSQL
16, plain SQL through `JdbcClient` (no ORM), schema managed by Flyway. Checkout only ever talks to the node's own
database, so it keeps working with the internet down.

## Architectural boundaries

```
web ───────────────┐
                   ▼
infrastructure ──► application ──► domain
```

- **domain**: `Money`, `Quantity`, `TaxRate`, `Product`, `OrderDraft`, `Order`, the events and exceptions. It uses the
  JDK and nothing else.
- **application**: `OrderService`, `KitchenService`, `OutboxDispatcher`, and the ports they need (`InventoryStore`,
  `OrderStore`, `TransactionRunner`, ...). These are plain classes with no Spring annotations; `ApplicationConfig` wires
  them. Transactions are reached through the `TransactionRunner` port, so the service decides where a transaction starts
  and ends without importing Spring.
- **infrastructure**: the JDBC adapters, the Spring transaction runner and the outbox poller.
- **web**: controllers, request/response DTOs and the mapping from exceptions to JSON errors.

The rule is that arrows point inwards, and `ArchitectureTest` fails the build if they don't: the domain and application
may not import Spring, JDBC or Jackson, `web` and `infrastructure` may not import each other, and there are no cycles.
The same test forbids `double`/`float` in business code and `Instant.now()` outside the injected `Clock`.

The payoff is that `OrderServiceTest` runs the whole checkout flow with in-memory fakes in milliseconds, and the domain
tests need no context at all.

**`JdbcClient`, not JPA.** Most of the correctness lives in specific SQL (a conditional `UPDATE`, an upsert,
`SKIP LOCKED`), and I wanted it written down, not generated. The price is more mapping code.

**Money.** `BigDecimal` plus `java.util.Currency`, never a float. Tax is rounded per line to the currency's minor unit
(2 for INR, 0 for JPY, 3 for KWD) with HALF_EVEN. Adding amounts in different currencies throws, and a payment in
another currency than the order is rejected.

## Concurrency strategy

**Stock (zero overselling).** One statement does the check and the change:

```sql
update inventory
set quantity = quantity - :n,
    version  = version + 1
where product_id = :id
  and quantity >= :n
```

Zero rows updated means not enough stock. Postgres locks the row for the statement, so a second terminal waits and then
sees the new number. I weighed `SELECT ... FOR UPDATE` (two round trips, lock held longer), an optimistic `version`
check (many retries on a popular product) and an in-process lock or queue (wrong as soon as a second process touches the
database). The atomic update was the simplest option that cannot lose an update.

**Deadlocks.** If two orders list the same products in opposite order, each can wait for the other. Every order
therefore locks its stock rows sorted by product id and takes the order-number row last, so no cycle can form.

**No pile-ups.** `lock_timeout` is 2 s, the pool has 20 connections and waiting for one times out after 3 s. A request
that gives up gets `503 TRY_AGAIN` with `Retry-After`; nothing was saved, so resending is safe. Tomcat runs on virtual
threads, so waiting on the database doesn't tie up a scarce thread.

**Fiscal numbers (`ORD-{BRANCH}-{YYYYMMDD}-{0001}`).** A `fiscal_counter` row per (branch, business date) is incremented
by `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`, inside the order's own transaction. Unique because the row is
locked until commit; gap-free because a rolled-back order rolls the counter back with it. A Postgres `SEQUENCE` would be
unique but is not transactional, so every failed order would leave a hole.
`anOrderThatFailsAfterGettingItsNumberDoesNotBurnTheNumber` exists to catch exactly that mistake. The time stored on the
order is read after the lock is held, so a higher number never has an earlier time.

The trade-off is real: orders of one branch queue on that single row from the moment they take their number until they
commit. That is acceptable for 30 to 50 terminals, and it is why the number is taken as late as possible. It would not
suit thousands of orders per second.

## Network retries

Terminals send an `Idempotency-Key` header (required) with every attempt at a sale. The key is stored on the order in a
`UNIQUE` column.

1. `placeOrder` first looks the key up and, if an order exists, returns it. That is the cheap path for a retry that
   comes seconds later.
2. Looking first is not enough, because two identical requests a millisecond apart can both find nothing. The `UNIQUE`
   constraint is the final judge. The request that loses fails on insert, its whole transaction (stock, number,
   everything) rolls back, and the service returns the winner's order.

So a retry never decrements stock twice and never uses a second number. A failed attempt (say, out of stock) saves
nothing, so the same key can be used again later.

Known limit: the same key sent with a different order body returns the first order and ignores the new body.
Stripe-style APIs reject that case by storing a fingerprint of the request; I did not build that.

## Downstream reliability (kitchen)

Checkout never calls the kitchen. When an order contains food that needs preparing, the same transaction that saves the
order also inserts an `OrderPlaced` row into `outbox_event`. Either both are committed or neither is, so there is no
ticket without an order and no order whose ticket request is lost, even if the node loses power right after the commit.

A poller wakes up every second and the dispatcher handles one event per transaction: lock the oldest due event
(`FOR UPDATE SKIP LOCKED`), create the kitchen ticket, mark the event published. If any step fails the whole transaction
rolls back and the event stays pending. Retries wait 1 s, 2 s, 4 s and so on up to 5 minutes, and after 10 failed
attempts the event is parked as `FAILED` so one bad event cannot block the ones behind it.

Delivery is at least once. A repeat is harmless because `kitchen_ticket.order_id` is `UNIQUE` and the insert uses
`ON CONFLICT DO NOTHING`.

Limits: polling adds up to about a second of delay, and published events are not deleted yet, so the table only grows.

## Distributed sync readiness

- Every key is a UUID v7 generated by the application, so two branches cannot collide and ids still sort roughly by
  creation time. Nothing uses a database sequence or `SERIAL`, and `SchemaIntegrationTest` fails if anything ever does.
- Tables that would be sent to Central HQ (`product`, `inventory`, `orders`, `kitchen_ticket`) all carry `branch_id`,
  `version` and `updated_at`. The same test checks this and fails when a new table is added without being classified as
  synced, a child row or local-only.
- Orders never change after they are placed, which makes them easy to replicate. Inventory is the part that can
  conflict, and its `version` goes up on every change. A real sync should send stock movements, not overwrite
  quantities.
- Order numbers include the branch code, so they cannot clash at HQ either.

Not built: the sync protocol itself, conflict resolution and a change feed. The model is prepared for them, nothing
more. Also note that `inventory.updated_at` uses the database clock and `orders.placed_at` the application clock.


