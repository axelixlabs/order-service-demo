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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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
    void findByIdLoadsOrderAndItsLazyGraphWithinTransaction() {
        PurchaseOrder saved = persistSampleOrder();

        PurchaseOrder found = orderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getOrderNumber()).isEqualTo("ORD-1");
        // Lazy associations resolve here only because the test is transactional;
        // in the hot path this is exactly what fans out into the demonstrated N+1.
        assertThat(found.getCustomer().getEmail()).isEqualTo("ada@example.com");
        assertThat(found.getItems()).hasSize(2);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void findOrdersWithItemsAppliesPagingAndFetchesItems() {
        // Two orders in the window, sharing one customer and catalogue.
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        customerRepository.save(customer);
        Category cat = categoryRepository.save(new Category("Electronics", "d"));
        Product keyboard = productRepository.save(new Product("SKU-KB", "Keyboard", "d", new BigDecimal("100.00"), 100, cat));
        Product mouse = productRepository.save(new Product("SKU-MS", "Mouse", "d", new BigDecimal("25.00"), 100, cat));
        for (int i = 1; i <= 2; i++) {
            PurchaseOrder order = new PurchaseOrder("ORD-" + i, customer);
            order.addItem(new OrderItem(keyboard, 2));
            order.addItem(new OrderItem(mouse, 4));
            orderRepository.save(order);
        }

        List<PurchaseOrder> firstPage = orderRepository.findOrdersWithItems(
                Instant.now().minusSeconds(3600),
                Instant.now().plusSeconds(3600),
                PageRequest.of(0, 1, Sort.by("createdAt").ascending()));

        // One order on the page, and its items are already initialized (fetch join).
        // Under the hood Hibernate paged this IN MEMORY (HHH000104) because of the
        // collection fetch — the point of the demo.
        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getItems()).hasSize(2);
    }

    @Test
    void streamByCreatedAtBetweenReturnsOrders() {
        persistSampleOrder();

        long count;
        try (var stream = orderRepository.streamByCreatedAtBetween(
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600))) {
            count = stream.count();
        }
        assertThat(count).isEqualTo(1);
    }
}
