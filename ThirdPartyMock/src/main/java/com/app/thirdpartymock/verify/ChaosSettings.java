package com.app.thirdpartymock.verify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giả lập hành vi của một API bên thứ ba "thật": chậm, lỗi 5xx, giới hạn số request/giây (429).
 * Mặc định tất cả = 0 (happy case). Đổi lúc đang chạy qua POST /admin/chaos.
 */
@Component
public class ChaosSettings {

    private volatile long delayMs;
    private volatile double errorRate;
    private volatile int rateLimitPerSecond;

    public ChaosSettings(@Value("${mock.simulate.delay-ms}") long delayMs,
                         @Value("${mock.simulate.error-rate}") double errorRate,
                         @Value("${mock.simulate.rate-limit-per-second}") int rateLimitPerSecond) {
        this.delayMs = delayMs;
        this.errorRate = errorRate;
        this.rateLimitPerSecond = rateLimitPerSecond;
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

    /** 0 = không giới hạn. */
    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(int rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }
}
