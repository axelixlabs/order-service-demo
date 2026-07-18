# Order Service Demo

A Spring Boot 3 / Java 25 purchase-order processing service demonstrating a
rich JPA domain model, hot-path and heavy/reporting APIs, and Kafka integration.

## Tech stack

- Spring Boot 3.5, Java 25 (Gradle toolchain)
- Spring Data JPA + Hibernate
- Flyway (versioned SQL migrations; Hibernate `ddl-auto` is `validate` only)
- PostgreSQL (production), H2 (tests)
- Spring for Apache Kafka
- Gradle (with wrapper)
- JUnit 5 + Mockito + AssertJ + Testcontainers

## Domain model

An order-processing system with the following entities and relationships:

```
Customer 1───N Address
Customer 1───N PurchaseOrder
Category 1───N Product
PurchaseOrder 1───N OrderItem N───1 Product
PurchaseOrder 1───1 Payment
PurchaseOrder 1───1 Shipment N───1 Address
```

`PurchaseOrder` is the aggregate root (named to avoid the reserved `ORDER`
keyword). Order status transitions are validated by a small state machine in
`OrderStatus`.

## APIs

### Hot paths (frequent, low-latency)

| Method | Path                          | Description                                    |
|--------|-------------------------------|------------------------------------------------|
| POST   | `/api/v1/orders`              | Create an order (locks stock, publishes Kafka) |
| GET    | `/api/v1/orders/{id}`         | Fetch a single order                           |
| PATCH  | `/api/v1/orders/{id}/status`  | Advance the order lifecycle                    |

Creating an order publishes an `OrderCreatedEvent` to the `order-events` Kafka
topic **after the transaction commits**. A sample `@KafkaListener` consumes it.

### Heavy paths (infrequent, read-intensive)

| Method | Path                                    | Description                                         |
|--------|-----------------------------------------|-----------------------------------------------------|
| GET    | `/api/v1/reports/orders/export`         | Streams a CSV of one customer's orders in a window  |
| GET    | `/api/v1/reports/orders`                | Paginated feed of one customer's orders with items  |

Both accept a required `customerId` plus ISO-8601 `from`/`to` query params, e.g.
`?customerId=1&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z`; the orders feed also
accepts `page` and `size`.

### Performance anti-patterns (this branch is the baseline)

The heavy endpoints originally shipped with three classic JPA/Hibernate traps —
an N+1 on `GET /orders/{id}`, an N+1 on the CSV export, and Hibernate
*in-memory pagination* on the orders feed — compounded by Open-Session-In-View
holding a DB connection through response serialization. They were load-tested to
quantify the damage and then fixed on the `axelix-applied` branch. See
[Performance under load](#performance-under-load-baseline-vs-fixed) for the
measured impact, the root causes, and the fixes.

## Running the whole stack

```bash
docker compose up --build
```

This starts PostgreSQL, Kafka (KRaft mode), the application on
`http://localhost:8080`, and the monitoring stack (Prometheus + Grafana). Demo
data (catalogue + one customer) is seeded on first boot when tables are empty.
For a large dataset, see [Loading test data](#loading-test-data).

| Service    | URL                       | Notes                          |
|------------|---------------------------|--------------------------------|
| App        | http://localhost:8080     | REST APIs + `/actuator`        |
| Prometheus | http://localhost:9090     | scrapes `app:8080` every 5s    |
| Grafana    | http://localhost:3000     | login `admin` / `admin`        |

## Monitoring

The app exposes Micrometer metrics at `/actuator/prometheus`. Prometheus scrapes
them and Grafana auto-provisions a dashboard (**Order Service — RSS, Startup &
Throughput**) tracking the three requested signals:

| Signal | Metric | Panel query |
|--|--|--|
| **RSS** | `process_resident_memory_bytes` | `process_resident_memory_bytes{application="order-service"}` |
| **Startup time** | `application_ready_time_seconds` / `application_started_time_seconds` | shown as a stat panel |
| **Throughput** | `http_server_requests_seconds_count` | `sum(rate(...[1m]))`, total and per `method`/`uri` |

RSS is not exposed by Micrometer out of the box, so `ProcessMemoryMetrics` adds a
custom gauge that reads `/proc/self/statm` (a Linux-container concern; it reports
`NaN` elsewhere). Startup and HTTP-throughput metrics are contributed
automatically by Spring Boot Actuator.

Everything under `monitoring/` is provisioned declaratively:

```
monitoring/
  prometheus/prometheus.yml                      # scrape config
  grafana/provisioning/datasources/prometheus.yml  # Prometheus datasource (uid: prometheus)
  grafana/provisioning/dashboards/dashboards.yml   # file-based dashboard provider
  grafana/dashboards/order-service.json            # the dashboard (Grafana model is JSON)
```

Grafana dashboards are JSON by design; only the provisioning (datasource +
provider) is YAML. Open Grafana → the dashboard loads with no manual import.

### Try it

```bash
# Create an order (customer 1, shipping address 1, two products)
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerId": 1,
        "shippingAddressId": 1,
        "paymentMethod": "CREDIT_CARD",
        "items": [
          {"productId": 1, "quantity": 2},
          {"productId": 4, "quantity": 1}
        ]
      }'

# Fetch it
curl http://localhost:8080/api/v1/orders/1

# Advance status
curl -X PATCH http://localhost:8080/api/v1/orders/1/status \
  -H 'Content-Type: application/json' -d '{"status":"PAID"}'

# Heavy: paginated orders feed for one customer (watch the logs for HHH90003004 in-memory paging)
curl 'http://localhost:8080/api/v1/reports/orders?customerId=1&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z&page=0&size=20'

# Heavy: CSV export for one customer
curl 'http://localhost:8080/api/v1/reports/orders/export?customerId=1&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z'
```

## Building and testing locally

```bash
./gradlew test        # run the unit + slice + JPA tests (no external services)
./gradlew bootJar     # build the executable jar
```

Tests run against H2 and Mockito, so no Postgres or Kafka is required for
`./gradlew test`.

## Configuration

Key environment variables (see `application.yml` for defaults):

| Variable                          | Default                                       |
|-----------------------------------|-----------------------------------------------|
| `SPRING_DATASOURCE_URL`           | `jdbc:postgresql://localhost:5432/orderdb`    |
| `SPRING_DATASOURCE_USERNAME`      | `orderuser`                                   |
| `SPRING_DATASOURCE_PASSWORD`      | `orderpass`                                   |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS`  | `localhost:9092`                              |
| `APP_SEED_ENABLED`                | `true`                                        |

## Loading test data

A bulk loader populates PostgreSQL with realistic data for performance demos:

| Entity           | Count        |
|------------------|--------------|
| Categories       | 10           |
| Products         | 1,000        |
| Customers        | 100,000      |
| Addresses        | 200,000      |
| Purchase orders  | 10,000,000   |

Orders are spread evenly (100 per customer), timestamps span ~3 years, and
status/payment/shipment fields follow plausible distributions.

**From the repo root** (starts Postgres, applies Flyway schema via a brief app
start if needed, then loads data — allow 30–60 minutes for the full 10M-order
load):

```bash
./scripts/load-test-data.sh
```

Or run the Python loader directly against a database that already has the
Flyway schema (`src/main/resources/db/migration/`):

```bash
pip install -r scripts/requirements.txt
# or: python3 -m venv scripts/.venv && scripts/.venv/bin/pip install -r scripts/requirements.txt
docker compose up -d app   # apply migrations once, then stop if you prefer
python scripts/load_test_data.py
```

Useful flags: `--seed 42` (reproducible data), `--customers N`, `--orders M`
(orders must be divisible by customers). Stop the app before loading if it is
already running so the truncate step is not blocked by open connections.

After loading, start the stack normally:

```bash
docker compose up -d
```

The built-in `DataSeeder` skips automatically when products already exist.

## Load testing (k6)

Two TypeScript k6 scenarios live under `load-tests/`:

**`order-flow.ts` — realistic user journey.** A 5-minute ramp
(15 → 23 → 45 → 23 → 15 virtual users) with think-time. Each virtual user maps to
a customer (`customerId = VU id` against bulk-loaded data) and fetches the last
two feed pages, creates three orders (30 s apart), transitions them through
PAID / PROCESSING / CANCELLED, re-reads the feed, and downloads the CSV export.
With its think-time this is a low-rate (~6 req/s) workload — healthy on both
branches.

```bash
# App must be running with test data loaded
./load-tests/run-order-flow.sh
# Or directly:
cd load-tests && k6 run order-flow.ts
# Small demo seed customer only (no bulk load):
SEED_MODE=true ./load-tests/run-order-flow.sh
```

**`stress.ts` — read-only stress test.** A `ramping-arrival-rate` scenario
(10 → 60 req/s over 3 minutes, **no think-time**) that hammers only the heavy
read paths — orders feed (random page), `getOrder` (random id), and a CSV export
10 % of the time. This is the workload used for the numbers in
[Performance under load](#performance-under-load-baseline-vs-fixed); it is what
actually stresses the pool and surfaces the anti-patterns.

```bash
cd load-tests && k6 run -e CUSTOMERS=10 -e ORDERS=20000 -e FEED_SIZE=10 stress.ts
```

Optional environment variables: `BASE_URL`, `CUSTOMER_COUNT`/`CUSTOMERS`,
`ORDERS`, `FEED_FROM`, `FEED_SIZE`. Install `@types/k6` for IDE support:
`cd load-tests && npm install`.

## Performance under load (baseline vs fixed)

The three anti-patterns were benchmarked with the read-only `stress.ts` load.
The baseline (this `main` branch) collapses under load; the `axelix-applied`
branch, with the fixes, does not. Numbers below are from a single host — absolute values
will vary, but the relative gap is large and reproducible.

### Reproducible setup

- **Stack:** `docker compose up -d` — Postgres 16, Kafka (KRaft), the app,
  Prometheus, Grafana.
- **Connection pool:** Hikari **max 10** (`application.yml`, identical on both
  branches — the bounded pool is what makes the anti-patterns bite).
- **Dataset** (deterministic via `--seed`, loads in seconds):

  ```bash
  ./scripts/load-test-data.sh --customers 10 --orders 20000 --seed 42
  ```

  → 10 categories, **1,000 products**, **10 customers** + 20 addresses,
  **20,000 orders (2,000 per customer)**, 50,000 line items, 20k payments, 20k
  shipments. *Few customers with many orders each* is deliberate: it makes the
  feed's in-memory pagination and the CSV export's per-row N+1 expensive.

- **Load:** `load-tests/stress.ts` — k6 `ramping-arrival-rate`, **10 → 60 req/s
  over 3 min, no think-time**. Each iteration = 1 orders-feed (random page) +
  1 `getOrder` (random id) + a CSV export 10 % of the time.
- **Heap:** exercised at a generous `-Xmx1g` and a constrained `-Xmx384m`
  (set via the app's `JAVA_OPTS`).

**To reproduce**, run the same steps on each branch and compare — e.g.:

```bash
git checkout main          # or axelix-applied
docker compose up -d --build
./scripts/load-test-data.sh --customers 10 --orders 20000 --seed 42
docker compose restart app          # start fresh so metric counters reset
cd load-tests && k6 run -e CUSTOMERS=10 -e ORDERS=20000 stress.ts
```

Read per-endpoint rate / error / latency from the Grafana **“Order Service — RED
per endpoint”** dashboard (or `/actuator/prometheus`); memory/GC from
`jvm_gc_memory_allocated_bytes_total`, `jvm_gc_pause_seconds_*`, and
`process_resident_memory_bytes`.

### Results — generous heap (`-Xmx1g`)

| Signal | `main` (baseline) | `axelix-applied` (fixed) |
|--|--|--|
| Requests served in 3 min | 6,231 — **4,074 dropped** (couldn't keep up) | **14,847** (0 dropped) |
| p95 latency | **17.1 s** | **10.6 ms** |
| CSV export mean latency | **31.6 s** | 11.5 ms |
| `GET /orders/{id}` mean | 4.96 s | 2.8 ms |
| `GET /reports/orders` mean | 5.28 s | 6.3 ms |
| Client-side failure rate (k6) | **1.92 %** | **0.00 %** |
| **CSV export error rate (5xx)** | **23.1 %** (116/502) | **0 %** |
| Bytes allocated | **85 GB** | 4.1 GB (**20.8× less**) |
| GC pauses / time in GC | **587 / 7.72 s** | 50 / 0.08 s (**~100× less GC**) |
| RSS peak | 1.15 GB | 1.15 GB (both fill the 1 GB heap) |

**Per-endpoint error rate:** only the CSV export returns outright **5xx** on the
baseline (**23 % → 0 %**). The feed and `getOrder` show ~0 % *server* errors on
both — but only because the baseline was so slow that k6 **dropped 4,074 requests
it never managed to send**. Their real regression is latency (seconds → ms) and
throughput collapse, not HTTP status.

### Results — constrained heap (`-Xmx384m`): the memory-footprint story

RSS looks the same above because both JVMs simply fill a 1 GB heap. The real
difference is the *working set* — shrink the heap and the baseline falls apart
while the fixed build is unaffected:

| Signal | `main` (baseline) | `axelix-applied` (fixed) |
|--|--|--|
| Requests served | 5,305 | **14,839** |
| CSV export error rate | 12.8 % | **0 %** |
| p95 latency | **52.8 s** | 20.9 ms |
| Bytes allocated | 64.5 GB | 3.9 GB (**16× less**) |
| GC pauses / time in GC | **1,035 / 8.49 s** | 48 / 0.06 s (**~137× less GC**) |

**`axelix-applied` runs identically well in 384 MB as in 1 GB** (≈14.8k requests,
0 errors, p95 ~21 ms either way). The baseline degrades *further* as the heap
shrinks — it GC-thrashes (1,035 pauses) just to stay alive. That is the footprint
reduction: the fixed build's per-request working set is tiny, so it thrives in a
fraction of the memory; the baseline needs a big heap **and still collapses**.

### What caused it (root causes → fixes)

| Endpoint | Root cause on `main` (this branch) | Effect | Fix (on `axelix-applied`) |
|--|--|--|--|
| `GET /reports/orders` | Collection `join fetch` + `Pageable` → Hibernate loads **all 2,000 of the customer's orders into heap and paginates in memory**, on every request regardless of page — plus N+1 (payment / shipment / customer / product per order). | The 85 GB of garbage, GC thrash, multi-second latency. | **Two-step:** page order **ids** in SQL (`LIMIT`/`OFFSET`), then one fetch-join for just that page. |
| `GET /orders/{id}` | Plain `findById`; items, each product, the customer, payment and shipment lazily load during serialization (N+1). | Extra round-trips per request under a busy pool. | One **fetch-join** query loads the whole response graph. |
| `GET /reports/orders/export` | Streams every row while lazily loading `customer.email` + `items.size()` per order, **holding one pooled connection for the entire multi-second stream**. | Pool exhaustion → **5xx**. | Flat **projection**: `customer.email` joined and `count(items)` computed in SQL — no per-row loads. |
| *(cross-cutting)* | **Open-Session-In-View enabled** → the connection is held through JSON serialization, so slow N+1 requests occupy the 10-connection pool for seconds. | Connection-acquisition timeouts under load → 5xx + throughput collapse. | `spring.jpa.open-in-view: false`; connections released when the service transaction ends (ms, not seconds). |

> The entity model had **no eager fetches to remove** — every `@ManyToOne` /
> `@OneToOne` was already `LAZY`. The damage came from *how the data was queried*
> (the three patterns above) plus OSIV, not from eager mappings.
