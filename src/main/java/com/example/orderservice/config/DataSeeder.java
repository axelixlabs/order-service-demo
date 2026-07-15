package com.example.orderservice.config;

import com.example.orderservice.domain.Address;
import com.example.orderservice.domain.AddressType;
import com.example.orderservice.domain.Category;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.Product;
import com.example.orderservice.repository.CategoryRepository;
import com.example.orderservice.repository.CustomerRepository;
import com.example.orderservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a small catalogue and a demo customer on startup so the APIs can be
 * exercised immediately. Enabled by default; disable with
 * {@code app.seed.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;

    public DataSeeder(CategoryRepository categoryRepository,
                      ProductRepository productRepository,
                      CustomerRepository customerRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Seed data already present, skipping.");
            return;
        }

        Category electronics = categoryRepository.save(new Category("Electronics", "Devices and gadgets"));
        Category books = categoryRepository.save(new Category("Books", "Printed and digital books"));

        productRepository.saveAll(List.of(
                new Product("SKU-KB-001", "Mechanical Keyboard", "Tactile switches", new BigDecimal("89.99"), 500, electronics),
                new Product("SKU-MS-002", "Wireless Mouse", "Ergonomic, silent click", new BigDecimal("39.50"), 800, electronics),
                new Product("SKU-HP-003", "Noise-cancelling Headphones", "Over-ear", new BigDecimal("199.00"), 300, electronics),
                new Product("SKU-BK-100", "Clean Code", "Robert C. Martin", new BigDecimal("34.95"), 1000, books),
                new Product("SKU-BK-101", "Effective Java", "Joshua Bloch", new BigDecimal("45.00"), 1000, books)));

        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1-555-0100");
        customer.addAddress(new Address(AddressType.SHIPPING, "1 Analytical Engine Way", "London", null, "EC1A", "UK"));
        customer.addAddress(new Address(AddressType.BILLING, "1 Analytical Engine Way", "London", null, "EC1A", "UK"));
        customerRepository.save(customer);

        log.info("Seeded {} products and demo customer id={}", productRepository.count(), customer.getId());
    }
}
