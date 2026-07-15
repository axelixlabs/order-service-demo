# Order Service Demo

A Spring Boot 3 / Java 17 purchase-order processing service demonstrating a
rich JPA domain model, hot-path and heavy/reporting APIs, and Kafka integration.

## Tech stack

- Spring Boot 3.3, Java 17 (Gradle toolchain)
- Spring Data JPA + Hibernate
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
| GET    | `/api/v1/reports/orders/export`         | Streams a CSV of all orders in a window (cursor)    |
| GET    | `/api/v1/reports/orders`                | Paginated orders feed, each order with its items    |

Both accept ISO-8601 `from`/`to` query params, e.g.
`?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z`; the orders feed also
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
data (catalogue + one customer) is seeded on first boot.

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

# Heavy: paginated orders feed (watch the logs for HHH90003004 in-memory paging)
curl 'http://localhost:8080/api/v1/reports/orders?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z&page=0&size=20'

# Heavy: CSV export
curl 'http://localhost:8080/api/v1/reports/orders/export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z'
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
