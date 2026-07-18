package com.example.orderservice.service;

import com.example.orderservice.domain.Category;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderItem;
import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.Product;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.web.dto.OrderCsvRow;
import com.example.orderservice.web.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private PurchaseOrderRepository orderRepository;

    @Test
    void exportOrdersCsvWritesHeaderAndRows() {
        // The repository now returns a flat projection (no entity, no lazy loads).
        OrderCsvRow row = new OrderCsvRow(
                "ORD-ABC", OrderStatus.CREATED, "ada@example.com", 1L,
                new BigDecimal("200.00"), Instant.parse("2026-01-01T00:00:00Z"));

        when(orderRepository.streamOrderCsvRows(eq(7L), any(), any()))
                .thenReturn(Stream.of(row));

        ReportService service = new ReportService(orderRepository);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportOrdersCsv(7L, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.strip().split("\n");
        assertThat(lines[0]).isEqualTo("order_number,status,customer_email,item_count,total_amount,created_at");
        assertThat(lines[1]).startsWith("ORD-ABC,CREATED,ada@example.com,1,200.00,");
    }

    @Test
    void ordersFeedPagesIdsThenFetchesDetailsAndMaps() {
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        ReflectionTestUtils.setField(customer, "id", 1L);
        Category cat = new Category("Electronics", "d");
        Product product = new Product("SKU-1", "Keyboard", "d", new BigDecimal("100.00"), 5, cat);

        PurchaseOrder order = new PurchaseOrder("ORD-ABC", customer);
        order.addItem(new OrderItem(product, 2));
        ReflectionTestUtils.setField(order, "id", 42L);

        // Step 1 returns the page of ids; step 2 fetches the full graph for them.
        when(orderRepository.findOrderIdsByCustomer(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(42L));
        when(orderRepository.findOrdersWithDetailsByIds(List.of(42L)))
                .thenReturn(List.of(order));

        ReportService service = new ReportService(orderRepository);
        List<OrderResponse> feed = service.ordersFeed(1L, Instant.now(), Instant.now(), 0, 20);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).orderNumber()).isEqualTo("ORD-ABC");
        assertThat(feed.get(0).items()).hasSize(1);
        assertThat(feed.get(0).totalAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void ordersFeedShortCircuitsWhenNoIdsOnPage() {
        when(orderRepository.findOrderIdsByCustomer(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        ReportService service = new ReportService(orderRepository);
        List<OrderResponse> feed = service.ordersFeed(1L, Instant.now(), Instant.now(), 5, 20);

        assertThat(feed).isEmpty();
    }
}
