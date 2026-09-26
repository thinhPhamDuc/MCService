package com.app.paymentservice.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cấu hình giả lập sự cố, dùng để quan sát OrderService phản ứng thế nào khi PaymentService chậm / lỗi.
 */
@Component
public class ChaosSettings {

    private volatile long delayMs;
    private volatile double errorRate;

    public ChaosSettings(@Value("${payment.simulate.delay-ms}") long delayMs,
                         @Value("${payment.simulate.error-rate}") double errorRate) {
        this.delayMs = delayMs;
        this.errorRate = errorRate;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }
}
