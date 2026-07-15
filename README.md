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
| GET    | `/api/v1/orders/{id}`         | Fetch a single order (single fetch-join query) |
| PATCH  | `/api/v1/orders/{id}/status`  | Advance the order lifecycle                    |

Creating an order publishes an `OrderCreatedEvent` to the `order-events` Kafka
topic **after the transaction commits**. A sample `@KafkaListener` consumes it.

### Heavy paths (infrequent, read-intensive)

| Method | Path                                    | Description                                         |
|--------|-----------------------------------------|-----------------------------------------------------|
| GET    | `/api/v1/reports/orders/export`         | Streams a CSV of all orders in a window (cursor)    |
| GET    | `/api/v1/reports/sales-summary`         | DB-side aggregation of units/revenue per product    |

Both accept ISO-8601 `from`/`to` query params, e.g.
`?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z`.

## Running the whole stack

```bash
docker compose up --build
```

This starts PostgreSQL, Kafka (KRaft mode), and the application on
`http://localhost:8080`. Demo data (catalogue + one customer) is seeded on first
boot.

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

# Heavy: sales summary
curl 'http://localhost:8080/api/v1/reports/sales-summary?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z'

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
