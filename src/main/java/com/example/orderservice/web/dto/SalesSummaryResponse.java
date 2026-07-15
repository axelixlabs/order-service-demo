package com.example.orderservice.web.dto;

import com.example.orderservice.repository.SalesSummaryRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SalesSummaryResponse(
        Instant from,
        Instant to,
        BigDecimal totalRevenue,
        long totalUnits,
        List<Row> rows) {

    public record Row(
            Long categoryId,
            String categoryName,
            Long productId,
            String productSku,
            String productName,
            long unitsSold,
            BigDecimal grossRevenue,
            long orderCount) {

        static Row from(SalesSummaryRow r) {
            return new Row(
                    r.getCategoryId(),
                    r.getCategoryName(),
                    r.getProductId(),
                    r.getProductSku(),
                    r.getProductName(),
                    r.getUnitsSold(),
                    r.getGrossRevenue(),
                    r.getOrderCount());
        }
    }

    public static SalesSummaryResponse of(Instant from, Instant to, List<SalesSummaryRow> rows) {
        List<Row> mapped = rows.stream().map(Row::from).toList();
        BigDecimal totalRevenue = mapped.stream()
                .map(Row::grossRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalUnits = mapped.stream().mapToLong(Row::unitsSold).sum();
        return new SalesSummaryResponse(from, to, totalRevenue, totalUnits, mapped);
    }
}
