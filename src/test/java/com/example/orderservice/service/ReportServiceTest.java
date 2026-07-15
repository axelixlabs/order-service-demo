package com.example.orderservice.service;

import com.example.orderservice.domain.Category;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderItem;
import com.example.orderservice.domain.Product;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.repository.SalesSummaryRow;
import com.example.orderservice.web.dto.SalesSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private PurchaseOrderRepository orderRepository;

    @Test
    void exportOrdersCsvWritesHeaderAndRows() {
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        Category cat = new Category("Electronics", "d");
        Product product = new Product("SKU-1", "Keyboard", "d", new BigDecimal("100.00"), 5, cat);

        PurchaseOrder order = new PurchaseOrder("ORD-ABC", customer);
        order.addItem(new OrderItem(product, 2));
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));

        when(orderRepository.streamByCreatedAtBetween(any(), any())).thenReturn(Stream.of(order));

        ReportService service = new ReportService(orderRepository);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportOrdersCsv(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.strip().split("\n");
        assertThat(lines[0]).isEqualTo("order_number,status,customer_email,item_count,total_amount,created_at");
        assertThat(lines[1]).startsWith("ORD-ABC,CREATED,ada@example.com,1,200.00,");
    }

    @Test
    void salesSummaryAggregatesTotals() {
        SalesSummaryRow row1 = row(1L, "Electronics", 100L, "SKU-1", "Keyboard", 3, new BigDecimal("300.00"), 2);
        SalesSummaryRow row2 = row(1L, "Electronics", 101L, "SKU-2", "Mouse", 5, new BigDecimal("125.00"), 4);
        when(orderRepository.aggregateSales(any(), any(), any())).thenReturn(List.of(row1, row2));

        ReportService service = new ReportService(orderRepository);
        SalesSummaryResponse response = service.salesSummary(Instant.now(), Instant.now());

        assertThat(response.rows()).hasSize(2);
        assertThat(response.totalUnits()).isEqualTo(8);
        assertThat(response.totalRevenue()).isEqualByComparingTo("425.00");
    }

    private static SalesSummaryRow row(Long catId, String catName, Long prodId, String sku,
                                       String name, long units, BigDecimal revenue, long orders) {
        return new SalesSummaryRow() {
            public Long getCategoryId() { return catId; }
            public String getCategoryName() { return catName; }
            public Long getProductId() { return prodId; }
            public String getProductSku() { return sku; }
            public String getProductName() { return name; }
            public long getUnitsSold() { return units; }
            public BigDecimal getGrossRevenue() { return revenue; }
            public long getOrderCount() { return orders; }
        };
    }
}
