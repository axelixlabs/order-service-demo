package com.example.orderservice.service;

import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.web.dto.OrderCsvRow;
import com.example.orderservice.web.dto.OrderResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

@Service
public class ReportService {

    private final PurchaseOrderRepository orderRepository;

    public ReportService(PurchaseOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * HEAVY PATH: stream one customer's orders in the window straight to the HTTP
     * response as CSV. It runs inside a read-only transaction and consumes a JDBC
     * cursor, so the row count never blows up heap.
     *
     * <p>The backing query is a flat {@link OrderCsvRow} projection: the customer
     * email and the line-item count are resolved in the same SQL statement, so
     * writing a row triggers no per-order lazy loads (the old N+1 is gone).
     */
    @Transactional(readOnly = true)
    public void exportOrdersCsv(Long customerId, Instant from, Instant to, OutputStream out) {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        try (Stream<OrderCsvRow> rows = orderRepository.streamOrderCsvRows(customerId, from, to)) {
            writeLine(writer, "order_number", "status", "customer_email", "item_count", "total_amount", "created_at");
            rows.forEach(row -> writeLine(writer,
                    row.orderNumber(),
                    row.status().name(),
                    row.customerEmail(),
                    String.valueOf(row.itemCount()),
                    row.totalAmount().toPlainString(),
                    row.createdAt().toString()));
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV report", e);
        }
    }

    /**
     * HEAVY PATH: a paginated feed of one customer's orders, each with its line items.
     *
     * <p>Two-step to keep pagination in the database: step 1 pages the order ids with
     * real SQL {@code LIMIT}/{@code OFFSET} (no collection fetch, so no in-memory
     * paging); step 2 fetches the full graph for exactly that page of ids in a single
     * query. This avoids both the {@code HHH000104} in-memory pagination and the N+1.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> ordersFeed(Long customerId, Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        List<Long> ids = orderRepository.findOrderIdsByCustomer(customerId, from, to, pageable);
        if (ids.isEmpty()) {
            return List.of();
        }
        return orderRepository.findOrdersWithDetailsByIds(ids).stream()
                .map(OrderResponse::from)
                .toList();
    }

    private void writeLine(Writer writer, String... cells) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(cells[i]));
            }
            sb.append('\n');
            writer.write(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV row", e);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
