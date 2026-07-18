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

### Intentional performance anti-patterns

This is a teaching demo, so three of the endpoints above deliberately ship with
classic JPA/Hibernate traps. Each is marked `ANTI-PATTERN (demo)` in the code
with an explanation and the fix. Set `spring.jpa.show-sql: true` and watch the
SQL to see them fire.

| Endpoint | Anti-pattern | What goes wrong |
|--|--|--|
| `GET /api/v1/orders/{id}` | **N+1** | Loaded via plain `findById`; building the response then lazily loads items, each item's product, the customer, payment and shipment — a separate SELECT each. |
| `GET /api/v1/reports/orders/export` | **N+1** | The streaming query fetches no associations, so every CSV row lazily loads its customer and items. |
| `GET /api/v1/reports/orders` | **In-memory pagination (by Hibernate)** | The repository query combines a collection `join fetch` with a `Pageable`. Hibernate can't translate the page to SQL `LIMIT`/`OFFSET`, so it loads the whole window and pages *in memory* — logging `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`. No manual slicing is written; Hibernate does the paging. |

The `OrderServiceTest`, `ReportServiceTest` and `PurchaseOrderRepositoryTest`
document the intended (correct) contracts; the fixes are described inline next
to each anti-pattern.

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

A TypeScript k6 scenario exercises the hot and heavy APIs with a 5-minute ramp
(30 → 45 → 90 → 45 → 30 virtual users). Each virtual user maps to a customer
(`customerId = VU id` against bulk-loaded data), then:

1. Fetches the last and second-to-last pages of the orders feed (10 per page)
2. Creates three orders (30 s apart), transitions them through PAID / PROCESSING /
   CANCELLED, re-reads the feed, and downloads the CSV export

```bash
# App must be running with test data loaded
./load-tests/run-order-flow.sh

# Or directly:
cd load-tests && k6 run order-flow.ts
```

For the small demo seed customer only (no bulk load):

```bash
SEED_MODE=true ./load-tests/run-order-flow.sh
```

Optional environment variables: `BASE_URL`, `CUSTOMER_COUNT`, `FEED_FROM`,
`FEED_SIZE`. Install `@types/k6` for IDE support: `cd load-tests && npm install`.
