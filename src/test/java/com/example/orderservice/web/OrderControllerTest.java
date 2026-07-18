package com.example.orderservice.web;

import com.example.orderservice.domain.Customer;
import com.example.orderservice.domain.OrderStatus;
import com.example.orderservice.domain.PurchaseOrder;
import com.example.orderservice.service.OrderService;
import com.example.orderservice.service.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private PurchaseOrder sampleOrder() {
        Customer customer = new Customer("Ada", "Lovelace", "ada@example.com", "+1");
        ReflectionTestUtils.setField(customer, "id", 1L);
        PurchaseOrder order = new PurchaseOrder("ORD-123", customer);
        ReflectionTestUtils.setField(order, "id", 42L);
        return order;
    }

    @Test
    void getReturnsOrder() throws Exception {
        when(orderService.getOrder(42L)).thenReturn(sampleOrder());

        mockMvc.perform(get("/api/v1/orders/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-123"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.customerName").value("Ada Lovelace"));
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(orderService.getOrder(99L)).thenThrow(ResourceNotFoundException.of("Order", 99L));

        mockMvc.perform(get("/api/v1/orders/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidatesRequestBody() throws Exception {
        // Missing items -> validation failure -> 400
        String body = objectMapper.writeValueAsString(Map.of(
                "customerId", 1,
                "shippingAddressId", 10,
                "paymentMethod", "CREDIT_CARD"));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStatusReturnsUpdatedOrder() throws Exception {
        PurchaseOrder order = sampleOrder();
        order.transitionTo(OrderStatus.PAID);
        when(orderService.updateStatus(eq(42L), any())).thenReturn(order);

        String body = objectMapper.writeValueAsString(Map.of("status", "PAID"));

        mockMvc.perform(patch("/api/v1/orders/42/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }
}
