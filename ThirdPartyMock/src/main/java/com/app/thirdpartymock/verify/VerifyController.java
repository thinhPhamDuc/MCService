package com.app.thirdpartymock.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * API bên thứ ba giả lập: xác minh khách hàng theo lô (bulk).
 * Kết quả tính từ dữ liệu đầu vào (không random) → gọi lại cùng dữ liệu luôn ra cùng kết quả,
 * nhờ vậy ImportService retry / chạy lại vẫn ghi ra đúng một kết quả.
 */
@RestController
public class VerifyController {

    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    private final ChaosSettings chaos;
    private final RateLimiter rateLimiter;
    private final int maxBatchSize;

    public VerifyController(ChaosSettings chaos, RateLimiter rateLimiter,
                            @Value("${mock.max-batch-size}") int maxBatchSize) {
        this.chaos = chaos;
        this.rateLimiter = rateLimiter;
        this.maxBatchSize = maxBatchSize;
    }

    public record VerifyRequest(String externalId, String fullName, String email, String phone, String country) {
    }

    public record VerifyResult(String externalId, boolean verified, int riskScore) {
    }

    /** Nhận tối đa {@code mock.max-batch-size} khách hàng / request, trả kết quả theo đúng thứ tự gửi lên. */
    @PostMapping("/v1/verify/bulk")
    public ResponseEntity<List<VerifyResult>> verifyBulk(@RequestBody List<VerifyRequest> items)
            throws InterruptedException {
        if (items.size() > maxBatchSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Batch too large: " + items.size() + " > " + maxBatchSize);
        }
        if (!rateLimiter.tryAcquire(chaos.getRateLimitPerSecond())) {
            log.warn("Rate limit exceeded ({} req/s), rejecting batch of {}", chaos.getRateLimitPerSecond(), items.size());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .build();
        }
        if (chaos.getDelayMs() > 0) {
            Thread.sleep(chaos.getDelayMs());
        }
        if (ThreadLocalRandom.current().nextDouble() < chaos.getErrorRate()) {
            throw new IllegalStateException("Simulated third-party failure for batch of " + items.size());
        }
        log.info("Verified batch size={}", items.size());
        return ResponseEntity.ok(items.stream().map(VerifyController::verify).toList());
    }

    static VerifyResult verify(VerifyRequest item) {
        int riskScore = Math.floorMod(String.valueOf(item.externalId()).hashCode(), 100);
        boolean validEmail = item.email() != null && item.email().contains("@");
        return new VerifyResult(item.externalId(), validEmail && riskScore < 90, riskScore);
    }

    /**
     * Bật/tắt giả lập sự cố khi đang chạy, ví dụ:
     * POST /admin/chaos?delayMs=200&errorRate=0.1&rateLimitPerSecond=50
     */
    @PostMapping("/admin/chaos")
    public Map<String, Object> chaos(@RequestParam(required = false) Long delayMs,
                                     @RequestParam(required = false) Double errorRate,
                                     @RequestParam(required = false) Integer rateLimitPerSecond) {
        if (delayMs != null) {
            chaos.setDelayMs(delayMs);
        }
        if (errorRate != null) {
            chaos.setErrorRate(errorRate);
        }
        if (rateLimitPerSecond != null) {
            chaos.setRateLimitPerSecond(rateLimitPerSecond);
        }
        log.warn("Chaos settings changed delayMs={} errorRate={} rateLimitPerSecond={}",
                chaos.getDelayMs(), chaos.getErrorRate(), chaos.getRateLimitPerSecond());
        return Map.of("delayMs", chaos.getDelayMs(), "errorRate", chaos.getErrorRate(),
                "rateLimitPerSecond", chaos.getRateLimitPerSecond());
    }
}
