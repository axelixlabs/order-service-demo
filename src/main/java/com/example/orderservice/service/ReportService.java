package com.example.orderservice.service;

import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.repository.PurchaseOrderRepository;
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
     * HEAVY PATH: stream every order in the window straight to the HTTP response
     * as CSV. It runs inside a read-only transaction and consumes a JDBC cursor,
     * so the row count never blows up heap.
     *
     * <p>ANTI-PATTERN (demo): the streaming query does not fetch associations,
     * so {@code order.getCustomer().getEmail()} and {@code order.getItems()}
     * below each lazily load per row — an N+1 that runs once for every order in
     * the export. FIX: {@code join fetch} the customer in the query and load item
     * counts via a projection or a batched secondary query.
     */
    @Transactional(readOnly = true)
    public void exportOrdersCsv(Instant from, Instant to, OutputStream out) {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        try (Stream<PurchaseOrder> orders = orderRepository.streamByCreatedAtBetween(from, to)) {
            writeLine(writer, "order_number", "status", "customer_email", "item_count", "total_amount", "created_at");
            orders.forEach(order -> writeLine(writer,
                    order.getOrderNumber(),
                    order.getStatus().name(),
                    order.getCustomer().getEmail(),
                    String.valueOf(order.getItems().size()),
                    order.getTotalAmount().toPlainString(),
                    order.getCreatedAt().toString()));
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV report", e);
        }
    }

    /**
     * HEAVY PATH: a paginated orders feed, each order with its line items.
     *
     * <p>ANTI-PATTERN (demo): the backing query {@code join fetch}es the items
     * collection AND takes a {@link Pageable}. Hibernate can't turn that page into
     * SQL {@code LIMIT}/{@code OFFSET}, so it fetches the whole window and pages IN
     * MEMORY (watch for {@code HHH000104} in the logs). Note there is no manual
     * slicing here — we just hand Hibernate a {@code Pageable} and it does the
     * (in-memory) paging for us, which is exactly the trap. See the repository
     * method for the fix.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> ordersFeed(Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return orderRepository.findOrdersWithItems(from, to, pageable).stream()
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
