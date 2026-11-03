# store-node

The ordering, inventory and kitchen-dispatch engine for one branch (an "edge node"). Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway. The reasoning behind the main decisions is in [DESIGN.md](DESIGN.md).

## Run it

You need JDK 21 and Docker running.

```
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Spring Boot starts the Postgres container from `compose.yaml` by itself (port 5460). The `dev` profile also loads four demo products, because without it the database is empty and there is nothing to order.

The API is then on http://localhost:8080. [requests.http](requests.http) has ready-made calls for the IntelliJ HTTP client.

## Run the tests

```
.\mvnw test
```

Docker has to be running: the integration tests start their own throwaway Postgres with Testcontainers and never touch the dev database. At the time of writing there are 106 tests and the whole run takes a couple of minutes.

## API

| Method and path | What it does |
|---|---|
| `GET /api/v1/products` | products with the stock available |
| `POST /api/v1/orders` | place an order. Header `Idempotency-Key` is required, `X-Terminal-Id` is optional. Returns `201` |
| `GET /api/v1/orders/{id}` | read an order back |
| `GET /api/v1/kitchen/tickets` | open kitchen tickets, oldest first |

Money and tax rates are strings in JSON (`"396.90"`), never numbers.

Errors come back as JSON with a stable `code`:

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_REQUEST`, `MISSING_HEADER` | the request is wrong in shape |
| 404 | `PRODUCT_NOT_FOUND`, `ORDER_NOT_FOUND` | unknown id |
| 409 | `OUT_OF_STOCK` | not enough stock |
| 422 | `PAYMENT_REJECTED`, `CURRENCY_MISMATCH`, `INVALID_ORDER` | breaks a business rule |
| 503 | `TRY_AGAIN` | the node was too busy (a lock or the connection pool); nothing was saved, send it again |

## Where each requirement is handled and tested

| Requirement from the brief | Where | What proves it |
|---|---|---|
| **A.** 30 to 50 terminals, no overselling, no deadlocks, no starvation | atomic conditional `UPDATE` in `JdbcInventoryStore`; stock rows locked in product-id order in `OrderService`; lock and connection timeouts in `application.yaml` | `ConcurrencyIntegrationTest`: `fiftyTerminalsFightingOverTwentyUnitsNeverOversell`, `ordersListingTheSameProductsInOppositeOrderDoNotDeadlock`, `aCheckoutStuckBehindALockGivesUpAndAsksTheTerminalToRetry`, `whenEveryConnectionIsBusy...` |
| **B.** gap-free numbers per branch per day, offline | `fiscal_counter` table and `JdbcFiscalSequence` | `fiftyOrdersAtOnceGetFiftyDifferentNumbersWithNoGaps`, `anOrderThatFailsAfterGettingItsNumberDoesNotBurnTheNumber`, `aLaterOrderNeverGetsASmallerNumber`, `FiscalSequenceIntegrationTest` |
| **C.** retries and duplicates | `Idempotency-Key`, unique column on `orders`, `OrderService.placeOrder` | `theSameOrderSentFiftyTimesAtOnceIsPlacedExactlyOnce`, `CheckoutIntegrationTest.sendingTheSameOrderTwice...`, `aFailedAttemptDoesNotUseUpTheKey` |
| **D.** kitchen never blocks checkout and is never lost | transactional outbox, `OutboxDispatcher`, `OutboxPoller` | `KitchenIntegrationTest` (broken kitchen, restart, duplicate delivery, rollback, poison event, two dispatchers) and `KitchenPollerIntegrationTest` |
| **E.** no floating point, no mixed currencies | `Money`, `TaxRate`, `Quantity` value objects | `MoneyTest`, `ValueObjectTest`, `OrderDraftTest`, and the floating-point rules in `ArchitectureTest` |
| Separation of concerns | `domain` / `application` / `infrastructure` / `web` | `ArchitectureTest` |
| Globally unique ids for sync | UUID v7 from `Ids`, `branch_id` + `version` + `updated_at` | `SchemaIntegrationTest`, `IdsTest` |
| Flyway migrations, no ORM generation | `src/main/resources/db/migration` (V1 to V5) | every integration test builds the schema from scratch; `SchemaIntegrationTest.everyMigrationRanSuccessfully` |
| Fast domain unit tests | `src/test/java/com/storenode/domain` | they use no Spring and no database |
| Concurrency stress test with 30 to 50 threads | `ConcurrencyIntegrationTest` | 50 threads for stock, numbering and duplicates, 40 for deadlocks |
| DESIGN.md | [DESIGN.md](DESIGN.md) | |

## Project layout

```
src/main/java/com/storenode
  domain/          money, orders, events, kitchen ticket; no framework
  application/     services and the ports they need (interfaces)
  infrastructure/  JDBC adapters, transaction runner, outbox poller, configuration
  web/             controllers, DTOs, error handling
src/main/resources
  db/migration/    V1 to V5, run by Flyway
  db/seed/         demo data, loaded only with the dev profile
```

## Try the crash scenario 

The tests simulate a restart. To see a real one:

1. Package the app (`.\mvnw package -DskipTests`) and make sure the database container is up (`docker compose up -d`).
2. Start it with a very slow outbox poller so the order sits in the outbox: `java -jar target\store-node-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --storenode.outbox.poll-interval-ms=600000`.
3. Place an order for the wrap (`WRAP-CHK`, it needs preparing). `GET /api/v1/kitchen/tickets` is still empty; in `psql` the row in `outbox_event` is `PENDING`.
4. Kill the process hard (Task Manager, or `taskkill /F`).
5. Start it again without the slow poller. A second or two later the ticket appears and the outbox row is `PUBLISHED`.

