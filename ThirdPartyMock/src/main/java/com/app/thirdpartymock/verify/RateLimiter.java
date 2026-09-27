package com.app.thirdpartymock.verify;

import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Rate limit kiểu "fixed window": đếm số request trong từng giây, vượt ngưỡng thì từ chối.
 * Đơn giản, đủ để giả lập 429 — API thật thường dùng token bucket / sliding window.
 */
@Component
public class RateLimiter {

    private final LongSupplier clockMillis;
    private long windowSecond = -1;
    private int count;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** @return true nếu request được phép đi qua trong giây hiện tại. */
    public synchronized boolean tryAcquire(int limitPerSecond) {
        if (limitPerSecond <= 0) {
            return true;
        }
        long second = clockMillis.getAsLong() / 1000;
        if (second != windowSecond) {
            windowSecond = second;
            count = 0;
        }
        if (count >= limitPerSecond) {
            return false;
        }
        count++;
        return true;
    }
}
