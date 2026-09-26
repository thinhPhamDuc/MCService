package com.app.orderservice.order;

import com.app.orderservice.client.PaymentClient;
import com.app.orderservice.client.UserClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderControllerTest {

    private static final String AUTH = "Bearer token";

    private PaymentClient paymentClient;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        UserClient userClient = mock(UserClient.class);
        when(userClient.validate(AUTH)).thenReturn(new UserClient.UserInfo(7L, "alice"));

        paymentClient = mock(PaymentClient.class);
        controller = new OrderController(orderRepository, userClient, paymentClient);
    }

    @Test
    void totalIsPriceTimesQuantity() {
        when(paymentClient.pay(any(), eq(7L), any()))
                .thenReturn(new PaymentClient.PaymentResponse(1L, null, "SUCCESS", "Paid"));

        Order order = controller.create(AUTH, new OrderController.CreateOrderRequest("iPhone", 3, new BigDecimal("1000")));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("3000");
        assertThat(order.getUserId()).isEqualTo(7L);
    }

    @Test
    void successfulPaymentMarksOrderPaid() {
        when(paymentClient.pay(any(), eq(7L), any()))
                .thenReturn(new PaymentClient.PaymentResponse(11L, null, "SUCCESS", "Paid"));

        Order order = controller.create(AUTH, new OrderController.CreateOrderRequest("iPhone", 1, new BigDecimal("1000")));

        assertThat(order.getStatus()).isEqualTo(Order.Status.PAID);
        assertThat(order.getPaymentId()).isEqualTo(11L);
    }

    @Test
    void declinedPaymentMarksOrderFailedWithReason() {
        when(paymentClient.pay(any(), eq(7L), any()))
                .thenReturn(new PaymentClient.PaymentResponse(12L, null, "FAILED", "Amount exceeds limit"));

        Order order = controller.create(AUTH, new OrderController.CreateOrderRequest("Car", 1, new BigDecimal("99000000")));

        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_FAILED);
        assertThat(order.getPaymentId()).isEqualTo(12L);
        assertThat(order.getFailureReason()).isEqualTo("Amount exceeds limit");
    }

    @Test
    void unreachablePaymentServiceMarksOrderFailedWithoutPaymentId() {
        when(paymentClient.pay(any(), eq(7L), any()))
                .thenThrow(new ResourceAccessException("Connection refused"));

        Order order = controller.create(AUTH, new OrderController.CreateOrderRequest("Mac", 1, new BigDecimal("100")));

        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_FAILED);
        assertThat(order.getPaymentId()).isNull();
        assertThat(order.getFailureReason()).contains("Connection refused");
    }
}
