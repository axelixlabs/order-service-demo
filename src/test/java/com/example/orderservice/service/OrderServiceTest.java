package com.example.orderservice.service;

import com.example.orderservice.domain.Address;
import com.example.orderservice.domain.AddressType;
import com.example.orderservice.domain.Category;
import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.Product;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.messaging.OrderCreatedEvent;
import com.example.orderservice.messaging.OrderEventPublisher;
import com.example.orderservice.repository.AddressRepository;
import com.example.orderservice.repository.CustomerRepository;
import com.example.orderservice.repository.ProductRepository;
import com.example.orderservice.repository.PurchaseOrderRepository;
import com.example.orderservice.web.dto.CreateOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private PurchaseOrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private OrderEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    private Customer customer;
    private Address shippingAddress;
    private Product keyboard;
    private Product mouse;

    @BeforeEach
    void setUp() {
        customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        ReflectionTestUtils.setField(customer, "id", 1L);

        shippingAddress = new Address(AddressType.SHIPPING, "1 Way", "London", null, "EC1A", "UK");
        customer.addAddress(shippingAddress);
        ReflectionTestUtils.setField(shippingAddress, "id", 10L);

        Category electronics = new Category("Electronics", "desc");
        keyboard = new Product("SKU-KB", "Keyboard", "d", new BigDecimal("100.00"), 5, electronics);
        ReflectionTestUtils.setField(keyboard, "id", 100L);
        mouse = new Product("SKU-MS", "Mouse", "d", new BigDecimal("25.00"), 5, electronics);
        ReflectionTestUtils.setField(mouse, "id", 101L);
    }

    @Test
    void createOrderReservesStockComputesTotalAndPublishesEvent() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(addressRepository.findById(10L)).thenReturn(Optional.of(shippingAddress));
        when(productRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(keyboard, mouse));
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest request = new CreateOrderRequest(
                1L, 10L, com.example.orderservice.domain.PaymentMethod.CREDIT_CARD,
                List.of(
                        new CreateOrderRequest.OrderLineRequest(100L, 2),
                        new CreateOrderRequest.OrderLineRequest(101L, 1)));

        PurchaseOrder order = orderService.createOrder(request);

        // 2 * 100 + 1 * 25
        assertThat(order.getTotalAmount()).isEqualByComparingTo("225.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getItems()).hasSize(2);
        assertThat(keyboard.getStockQuantity()).isEqualTo(3);
        assertThat(mouse.getStockQuantity()).isEqualTo(4);
        assertThat(order.getPayment()).isNotNull();
        assertThat(order.getShipment()).isNotNull();

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishOrderCreated(captor.capture());
        assertThat(captor.getValue().totalAmount()).isEqualByComparingTo("225.00");
        assertThat(captor.getValue().items()).hasSize(2);
    }

    @Test
    void createOrderFailsWhenStockInsufficient() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(addressRepository.findById(10L)).thenReturn(Optional.of(shippingAddress));
        when(productRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(keyboard));

        CreateOrderRequest request = new CreateOrderRequest(
                1L, 10L, com.example.orderservice.domain.PaymentMethod.CREDIT_CARD,
                List.of(new CreateOrderRequest.OrderLineRequest(100L, 999)));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");

        verify(eventPublisher, never()).publishOrderCreated(any());
    }

    @Test
    void createOrderRejectsAddressBelongingToAnotherCustomer() {
        Customer other = new Customer("Grace", "Hopper", "grace@example.com", "+1");
        ReflectionTestUtils.setField(other, "id", 2L);
        Address foreign = new Address(AddressType.SHIPPING, "x", "y", null, "z", "UK");
        other.addAddress(foreign);
        ReflectionTestUtils.setField(foreign, "id", 20L);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(addressRepository.findById(20L)).thenReturn(Optional.of(foreign));

        CreateOrderRequest request = new CreateOrderRequest(
                1L, 20L, com.example.orderservice.domain.PaymentMethod.PAYPAL,
                List.of(new CreateOrderRequest.OrderLineRequest(100L, 1)));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void updateStatusEnforcesLegalTransition() {
        PurchaseOrder order = new PurchaseOrder("ORD-1", customer);
        ReflectionTestUtils.setField(order, "id", 55L);
        when(orderRepository.findById(55L)).thenReturn(Optional.of(order));

        PurchaseOrder updated = orderService.updateStatus(55L, OrderStatus.PAID);
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void updateStatusRejectsIllegalTransition() {
        PurchaseOrder order = new PurchaseOrder("ORD-1", customer);
        ReflectionTestUtils.setField(order, "id", 55L);
        when(orderRepository.findById(55L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateStatus(55L, OrderStatus.DELIVERED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getOrderThrowsWhenMissing() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orderService.getOrder(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
