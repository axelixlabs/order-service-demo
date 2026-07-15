package com.example.orderservice.service;

import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.repository.SalesSummaryRow;
import com.example.orderservice.web.dto.SalesSummaryResponse;
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
     * as CSV. Because it runs inside a read-only transaction and consumes a JDBC
     * cursor, memory stays flat regardless of how many rows match.
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
     * HEAVY PATH: aggregate sales per product/category over a window. The heavy
     * lifting (grouping and summing across potentially many order items) runs in
     * the database; only the compact rollup crosses the wire.
     */
    @Transactional(readOnly = true)
    public SalesSummaryResponse salesSummary(Instant from, Instant to) {
        List<SalesSummaryRow> rows = orderRepository.aggregateSales(from, to, OrderStatus.CANCELLED);
        return SalesSummaryResponse.of(from, to, rows);
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
