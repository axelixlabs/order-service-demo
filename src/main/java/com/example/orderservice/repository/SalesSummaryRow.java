package com.example.orderservice.repository;

import java.math.BigDecimal;

/**
 * Spring Data projection for the aggregated sales-summary report.
 */
public interface SalesSummaryRow {

    Long getCategoryId();

    String getCategoryName();

    Long getProductId();

    String getProductSku();

    String getProductName();

    long getUnitsSold();

    BigDecimal getGrossRevenue();

    long getOrderCount();
}
