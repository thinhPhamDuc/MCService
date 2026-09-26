package com.app.paymentservice.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentRepository paymentRepository;
    private final ChaosSettings chaos;
    private final BigDecimal maxAmount;

    public PaymentController(PaymentRepository paymentRepository, ChaosSettings chaos,
                             @Value("${payment.max-amount}") BigDecimal maxAmount) {
        this.paymentRepository = paymentRepository;
        this.chaos = chaos;
        this.maxAmount = maxAmount;
    }

    public record PaymentRequest(Long orderId, Long userId, BigDecimal amount) {
    }

    public record PaymentResponse(Long paymentId, Long orderId, String status, String message) {
        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getStatus().name(), p.getMessage());
        }
    }

    /** OrderService gọi để thanh toán cho 1 đơn hàng. */
    @PostMapping("/payments")
    public PaymentResponse pay(@RequestBody PaymentRequest request) throws InterruptedException {
        log.info("Processing payment orderId={} userId={} amount={}",
                request.orderId(), request.userId(), request.amount());

        // Sự cố kỹ thuật giả lập: chậm và/hoặc lỗi 500
        if (chaos.getDelayMs() > 0) {
            log.warn("Simulating slow payment: sleeping {} ms", chaos.getDelayMs());
            Thread.sleep(chaos.getDelayMs());
        }
        if (ThreadLocalRandom.current().nextDouble() < chaos.getErrorRate()) {
            throw new IllegalStateException("Simulated payment gateway failure for orderId=" + request.orderId());
        }

        // Lỗi nghiệp vụ: vượt hạn mức -> thanh toán FAILED (không phải lỗi hệ thống)
        Payment payment;
        if (request.amount().compareTo(maxAmount) > 0) {
            payment = new Payment(request.orderId(), request.userId(), request.amount(),
                    Payment.Status.FAILED, "Amount exceeds limit " + maxAmount);
            log.warn("Payment declined orderId={} reason={}", request.orderId(), payment.getMessage());
        } else {
            payment = new Payment(request.orderId(), request.userId(), request.amount(),
                    Payment.Status.SUCCESS, "Paid");
        }
        payment = paymentRepository.save(payment);
        log.info("Payment done paymentId={} orderId={} status={}",
                payment.getId(), payment.getOrderId(), payment.getStatus());
        return PaymentResponse.from(payment);
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    /**
     * Bật/tắt giả lập sự cố khi service đang chạy, ví dụ:
     * POST /admin/chaos?delayMs=5000&errorRate=0.5
     */
    @PostMapping("/admin/chaos")
    public Map<String, Object> chaos(@RequestParam(required = false) Long delayMs,
                                     @RequestParam(required = false) Double errorRate) {
        if (delayMs != null) {
            chaos.setDelayMs(delayMs);
        }
        if (errorRate != null) {
            chaos.setErrorRate(errorRate);
        }
        log.warn("Chaos settings changed delayMs={} errorRate={}", chaos.getDelayMs(), chaos.getErrorRate());
        return Map.of("delayMs", chaos.getDelayMs(), "errorRate", chaos.getErrorRate());
    }
}
