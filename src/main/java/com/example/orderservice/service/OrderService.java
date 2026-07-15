package com.example.orderservice.service;

import com.example.orderservice.domain.Address;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderItem;
import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.Payment;
import com.example.orderservice.domain.Product;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.domain.Shipment;
import com.example.orderservice.messaging.OrderCreatedEvent;
import com.example.orderservice.messaging.OrderEventPublisher;
import com.example.orderservice.repository.AddressRepository;
import com.example.orderservice.repository.CustomerRepository;
import com.example.orderservice.repository.ProductRepository;
import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.web.dto.CreateOrderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final PurchaseOrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final AddressRepository addressRepository;
    private final OrderEventPublisher eventPublisher;

    public OrderService(PurchaseOrderRepository orderRepository,
                        CustomerRepository customerRepository,
                        ProductRepository productRepository,
                        AddressRepository addressRepository,
                        OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.addressRepository = addressRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * HOT PATH: create an order. Reserves stock under a row lock, persists the
     * order graph, and publishes an {@link OrderCreatedEvent} to Kafka only
     * after the surrounding transaction commits.
     */
    @Transactional
    public PurchaseOrder createOrder(CreateOrderRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", request.customerId()));

        Address shippingAddress = addressRepository.findById(request.shippingAddressId())
                .orElseThrow(() -> ResourceNotFoundException.of("Address", request.shippingAddressId()));
        if (!shippingAddress.getCustomer().getId().equals(customer.getId())) {
            throw new IllegalArgumentException("Shipping address does not belong to customer " + customer.getId());
        }

        // Lock all referenced products up front to prevent overselling.
        List<Long> productIds = request.items().stream()
                .map(CreateOrderRequest.OrderLineRequest::productId)
                .distinct()
                .toList();
        Map<Long, Product> products = productRepository.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        PurchaseOrder order = new PurchaseOrder(generateOrderNumber(), customer);
        for (CreateOrderRequest.OrderLineRequest line : request.items()) {
            Product product = products.get(line.productId());
            if (product == null) {
                throw ResourceNotFoundException.of("Product", line.productId());
            }
            product.decreaseStock(line.quantity());
            order.addItem(new OrderItem(product, line.quantity()));
        }

        Payment payment = new Payment(order.getTotalAmount(), request.paymentMethod());
        order.attachPayment(payment);
        order.attachShipment(new Shipment(shippingAddress, "PENDING_ASSIGNMENT"));

        PurchaseOrder saved = orderRepository.save(order);

        publishAfterCommit(toEvent(saved));
        return saved;
    }

    /** HOT PATH: fetch a fully-populated order for display. */
    @Transactional(readOnly = true)
    public PurchaseOrder getOrder(Long id) {
        return orderRepository.findDetailedById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
    }

    /** HOT PATH: advance the order through its lifecycle. */
    @Transactional
    public PurchaseOrder updateStatus(Long id, OrderStatus target) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
        order.transitionTo(target);
        return order;
    }

    private OrderCreatedEvent toEvent(PurchaseOrder order) {
        List<OrderCreatedEvent.Line> lines = order.getItems().stream()
                .map(i -> new OrderCreatedEvent.Line(
                        i.getProduct().getId(), i.getProduct().getSku(), i.getQuantity(), i.getLineTotal()))
                .toList();
        return new OrderCreatedEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer().getId(),
                order.getTotalAmount(),
                order.getPayment().getMethod().name(),
                lines,
                Instant.now());
    }

    private void publishAfterCommit(OrderCreatedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishOrderCreated(event);
                }
            });
        } else {
            eventPublisher.publishOrderCreated(event);
        }
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }
}
