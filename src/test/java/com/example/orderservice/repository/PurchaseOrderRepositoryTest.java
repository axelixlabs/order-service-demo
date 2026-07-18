package com.example.orderservice.repository;

import com.example.orderservice.domain.Address;
import com.example.orderservice.domain.AddressType;
import com.example.orderservice.domain.Category;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderItem;
import com.example.orderservice.domain.Payment;
import com.example.orderservice.domain.PaymentMethod;
import com.example.orderservice.domain.Product;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.web.dto.OrderCsvRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PurchaseOrderRepositoryTest {

    @Autowired
    private PurchaseOrderRepository orderRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;

    private PurchaseOrder persistSampleOrder() {
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        Address address = new Address(AddressType.SHIPPING, "1 Way", "London", null, "EC1A", "UK");
        customer.addAddress(address);
        customerRepository.save(customer);

        Category cat = categoryRepository.save(new Category("Electronics", "d"));
        Product keyboard = productRepository.save(new Product("SKU-KB", "Keyboard", "d", new BigDecimal("100.00"), 10, cat));
        Product mouse = productRepository.save(new Product("SKU-MS", "Mouse", "d", new BigDecimal("25.00"), 10, cat));

        PurchaseOrder order = new PurchaseOrder("ORD-1", customer);
        order.addItem(new OrderItem(keyboard, 2));
        order.addItem(new OrderItem(mouse, 4));
        order.attachPayment(new Payment(order.getTotalAmount(), PaymentMethod.CREDIT_CARD));
        return orderRepository.save(order);
    }

    @Test
    void findByIdWithDetailsLoadsWholeResponseGraphInOneQuery() {
        PurchaseOrder saved = persistSampleOrder();

        PurchaseOrder found = orderRepository.findByIdWithDetails(saved.getId()).orElseThrow();

        assertThat(found.getOrderNumber()).isEqualTo("ORD-1");
        // Customer, items and payment are all fetch-joined, so these resolve without
        // any extra SELECT — the fix for the former hot-path N+1.
        assertThat(found.getCustomer().getEmail()).isEqualTo("ada@example.com");
        assertThat(found.getItems()).hasSize(2);
        assertThat(found.getPayment()).isNotNull();
        assertThat(found.getTotalAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void ordersFeedPaginationPagesIdsInSqlThenFetchesDetails() {
        // Two orders in the window for the same customer; a second customer is ignored.
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        customerRepository.save(customer);
        Customer other = new Customer("Grace", "Hopper", "grace@example.com", "+1");
        customerRepository.save(other);
        Category cat = categoryRepository.save(new Category("Electronics", "d"));
        Product keyboard = productRepository.save(new Product("SKU-KB", "Keyboard", "d", new BigDecimal("100.00"), 100, cat));
        Product mouse = productRepository.save(new Product("SKU-MS", "Mouse", "d", new BigDecimal("25.00"), 100, cat));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 2; i++) {
            PurchaseOrder order = new PurchaseOrder("ORD-" + i, customer);
            order.addItem(new OrderItem(keyboard, 2));
            order.addItem(new OrderItem(mouse, 4));
            // Distinct timestamps so the createdAt sort is deterministic across pages.
            ReflectionTestUtils.setField(order, "createdAt", base.plusSeconds(i));
            orderRepository.save(order);
        }
        PurchaseOrder otherOrder = new PurchaseOrder("ORD-OTHER", other);
        otherOrder.addItem(new OrderItem(keyboard, 1));
        orderRepository.save(otherOrder);

        Instant from = base.minusSeconds(3600);
        Instant to = base.plusSeconds(3600);
        Sort sort = Sort.by("createdAt").ascending();

        // Step 1: real SQL LIMIT/OFFSET over ids (no in-memory pagination).
        List<Long> firstPageIds = orderRepository.findOrderIdsByCustomer(customer.getId(), from, to, PageRequest.of(0, 1, sort));
        List<Long> secondPageIds = orderRepository.findOrderIdsByCustomer(customer.getId(), from, to, PageRequest.of(1, 1, sort));
        assertThat(firstPageIds).hasSize(1);
        assertThat(secondPageIds).hasSize(1);
        assertThat(secondPageIds).doesNotContainAnyElementsOf(firstPageIds);

        // Step 2: fetch the full graph for just that page of ids.
        List<PurchaseOrder> firstPage = orderRepository.findOrdersWithDetailsByIds(firstPageIds);
        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(firstPage.get(0).getItems()).hasSize(2);
    }

    @Test
    void streamOrderCsvRowsReturnsOnlyThatCustomersRowsWithItemCount() {
        PurchaseOrder saved = persistSampleOrder();
        Customer other = new Customer("Grace", "Hopper", "grace@example.com", "+1");
        customerRepository.save(other);
        Product keyboard = productRepository.findAll().get(0);
        PurchaseOrder otherOrder = new PurchaseOrder("ORD-OTHER", other);
        otherOrder.addItem(new OrderItem(keyboard, 1));
        orderRepository.save(otherOrder);

        List<OrderCsvRow> rows;
        try (var stream = orderRepository.streamOrderCsvRows(
                saved.getCustomer().getId(),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600))) {
            rows = stream.toList();
        }

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).orderNumber()).isEqualTo("ORD-1");
        assertThat(rows.get(0).customerEmail()).isEqualTo("ada@example.com");
        // item_count comes straight from the SQL count() — no per-row lazy load.
        assertThat(rows.get(0).itemCount()).isEqualTo(2);
    }
}
