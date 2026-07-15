package com.example.orderservice.web;

import com.example.orderservice.service.ReportService;
import com.example.orderservice.web.dto.SalesSummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;

/**
 * Heavy/reporting API surface. These endpoints read large volumes of data and
 * build expensive responses; they are expected to be called infrequently.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Heavy path #1 — stream a CSV export of every order in the window. The body
     * is written incrementally via {@link StreamingResponseBody} so the servlet
     * container flushes chunks to the client without buffering the whole file.
     */
    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportOrders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        StreamingResponseBody body = out -> reportService.exportOrdersCsv(from, to, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    /**
     * Heavy path #2 — aggregated sales summary per product/category. The grouping
     * and summation are pushed down to the database.
     */
    @GetMapping("/sales-summary")
    public SalesSummaryResponse salesSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return reportService.salesSummary(from, to);
    }
}
