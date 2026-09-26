package com.app.orderservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record PaymentRequest(Long orderId, Long userId, BigDecimal amount) {
    }

    public record PaymentResponse(Long paymentId, Long orderId, String status, String message) {
    }

    /** Có thể ném RestClientException nếu PaymentService lỗi / timeout; OrderService tự xử lý. */
    public PaymentResponse pay(Long orderId, Long userId, BigDecimal amount) {
        return restClient.post()
                .uri("/payments")
                .body(new PaymentRequest(orderId, userId, amount))
                .retrieve()
                .body(PaymentResponse.class);
    }
}
