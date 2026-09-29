# Stock API contract

## Meaning and invariant

All quantities describe a product within its authenticated shop. Tracking begins at
`stockTrackingStartedAt`; it is not a promise of lifetime history.

```
totalStock       = openingBalance + SUM(ADD_STOCK.quantity)
soldQuantity     = SUM(successful tracked POS + restaurant SALE quantities)
stockCorrection  = SUM(STOCK_CORRECTION signed quantities)
remainingStock  = totalStock - soldQuantity + stockCorrection
```

Corrections do not change totalStock or soldQuantity. Sold is gross successful
checkout quantity since tracking started. Refund/cancellation netting is not
implemented by this change; a future return operation must explicitly specify
returned quantities and whether inventory is restored. Do not repurpose a stock
correction as a sale/refund or add historicalSoldQuantity to the balance equation.

Example: create 100, sell 30, add 50 -> totalStock 150, soldQuantity 30,
remainingStock 120, stockCorrection 0. Correct by -2 -> remainingStock 118,
stockCorrection -2; totalStock and soldQuantity stay 150 and 30.

## Product response (list, detail, barcode, create, edit, stock operation)

Additional top-level JSON fields:

```ts
interface ProductStockFields {
  totalStock: number;
  soldQuantity: number;
  remainingStock: number;
  stockCorrection: number; // signed; frontend must accept negative values
  openingBalance: number;
  historicalSoldQuantity: number;
  stockTrackingStartedAt: string; // ISO-8601 instant
  stockTrackingBasis: "OPENING_BALANCE" | "FROM_CREATION";
  product_quantity_amount: number; // compatibility alias of remainingStock
}
```

Use labels `Total Stock`, `Sold since tracking`, `Remaining`, `Stock Correction`.
For OPENING_BALANCE, visibly label total as `Tracked total stock` and show the
tracking date and opening balance. It is NOT historical original stock.
Do not map totalStock to an immutable initialStock field or correction to an
editedStock field. Existing frontend optionalQuantity() rejects negatives: use a
signed number parser for stockCorrection. Historical sales have a separate label,
`Recorded sales before tracking (available records only)`.

For an existing product with current balance 28 and 52 recorded completed sales,
the migration produces openingBalance=28, totalStock=28, soldQuantity=0,
remainingStock=28, historicalSoldQuantity=52, basis=OPENING_BALANCE. It never
invents totalStock=80. Existing unmatched receipt items remain in the database and
cannot be attributed to a current product. Historical sales are a cutover snapshot,
not a complete lifetime or partial-refund-aware report.

## Create and metadata edit

`POST /api/products` retains multipart `product_quantity_amount` as the starting
quantity (omitted means 0). Creates one OPENING_BALANCE movement with
stockTrackingBasis=FROM_CREATION. Up to two decimal places, nonnegative.

`PUT /api/products/{id}` is metadata-only: omit `product_quantity_amount` entirely.
Sending it returns 400, even if unchanged. Do not silently convert an old edit form
into an ADD_STOCK request. Metadata and availability writes also lock the product
to avoid overwriting a concurrently changed balance.

Tracked products cannot be physically deleted: DELETE returns 409. Use the existing
availability endpoint to disable sales while retaining the ledger and product links.

## Add stock / correction

`POST /api/products/{id}/stock`, JSON, normal product feature authorization:

```json
{"requestId":"unique-operation-uuid","operation":"ADD_STOCK","quantity":50,"reason":"Delivery"}
```

```json
{"requestId":"another-operation-uuid","operation":"STOCK_CORRECTION","quantity":-2,"reason":"Physical count discrepancy"}
```

ADD_STOCK quantity must be positive. STOCK_CORRECTION is a nonzero signed delta
(not an absolute target count), requires a nonblank reason, and must not make
remaining stock negative. Reason is at most 500 characters. Quantity has at most
two decimal places and magnitude <= 9999999999.99 (existing remaining column limit).
Returns ProductResponse. Another shop's product returns 404.

## Exactly-once retries

Both existing POS receipt-create and restaurant payment-create JSON bodies now
require `requestId`; other fields remain unchanged. Stock operations require it too.
Use a UUID generated once per business action, persisted alongside the pending
checkout. Reuse the SAME ID and payload for a network timeout/retry; generate a new
ID only for a genuinely new action. Do not use a new ID after an ambiguous response.

IDs accept 1-100 ASCII letters, digits, hyphens or underscores. Missing/invalid ID
returns 400. Same shop/ID and same deserialized request payload returns the saved
original response without another receipt/payment, stock change, or movement.
Changed payload or channel/product with the same ID returns 409. The namespace is
shared across POS, restaurant, and stock operations within a shop; a single sale
must not be submitted through both checkout APIs with different IDs. The server
cannot infer that unrelated new IDs represent the same real-world sale.

PostgreSQL transaction advisory lock serializes first requests and retries; product
PESSIMISTIC_WRITE locks serialize balance changes. Product totals, movement,
receipt/payment, and saved idempotency result commit or roll back together. A failed
transaction does not consume the ID. A replay returns the original response snapshot;
GET the product again to display its latest balance. Keep request records for at least
as long as clients can retry; this implementation does not expire them.

## Migration and deployment

V26 is additive: preserves existing product balances, legacy stock, receipts and
order items; snapshots recorded completed POS and paid restaurant sales separately;
creates one opening movement per existing product. It rejects null/negative opening
balances instead of guessing. Constraints enforce the balance equation and unique
movement/request references. No existing migration is rewritten.

Coordinate frontend rollout (new requestId, edit form omits quantity, new summary
fields). Stop all old product/checkout writers before migration and keep them stopped
until the new backend is deployed. Flyway runs V26 transactionally and locks source
tables while taking the opening/historical snapshot. Old backend code cannot safely
write after V26 because it does not update the new counters. Back up the database
before deployment. The migration has been tested against disposable PostgreSQL;
applying it to the configured POS database is a separate deployment step.

## Tests

Stock database tests require explicit opt-in. The existing application context smoke
test also requires a database and a test JWT key. To run the complete suite without
using any POS credentials, use the following process-local test settings.
For a disposable local PostgreSQL database named `stock_test` only:

```powershell
$env:STOCK_TEST_URL='jdbc:postgresql://127.0.0.1:55439/stock_test'
$env:SPRING_DATASOURCE_URL=$env:STOCK_TEST_URL
$env:SPRING_DATASOURCE_USERNAME='postgres'
$env:SPRING_DATASOURCE_PASSWORD=''
$env:SPRING_FLYWAY_ENABLED='false'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO='create-drop'
$testKeyBytes=New-Object byte[] 64
$testRng=[Security.Cryptography.RandomNumberGenerator]::Create()
$testRng.GetBytes($testKeyBytes)
$testRng.Dispose()
$env:APP_JWT_SECRET=[Convert]::ToBase64String($testKeyBytes)
$env:JWT_SECRET=$env:APP_JWT_SECRET
.\mvnw.cmd test
```

Use a dedicated shell and close it afterward so these test settings are not reused
to launch the application. The local disposable test server uses a postgres role
with trust authentication and must be bound to loopback only.

The integration test creates/drops tables in this database. Never point it at POS
data. Migration fixtures use separate transactional schemas and roll back. Tests
cover opening snapshots, filtering cancelled/refunded historical sales, constraints,
100/30/+50, signed correction, invalid input, shop isolation, retries, concurrent
oversell prevention, POS and restaurant movements, and failed-checkout rollback.

Validation result: 74 tests, 0 failures, 0 errors, 0 skipped with PostgreSQL 16.
17 of these are the new stock unit/controller/migration/integration tests.
