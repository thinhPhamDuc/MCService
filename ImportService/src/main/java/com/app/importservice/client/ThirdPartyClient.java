package com.app.importservice.client;

import com.app.importservice.common.RequestIdFilter;
import com.app.importservice.config.ImportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Gọi API bulk của bên thứ ba, có thử lại:
 * <ul>
 *   <li>5xx / timeout / mất kết nối → thử lại tối đa {@code maxAttempts} lần, chờ 200 → 400 → 800 ms (exponential backoff)</li>
 *   <li>429 Too Many Requests → chờ theo {@code Retry-After} rồi gửi lại, KHÔNG tính vào số lần thử
 *       (không phải lỗi của request, chỉ là gửi quá nhanh), tối đa {@code maxRateLimitWaits} lần</li>
 *   <li>4xx khác → lỗi của chính dữ liệu gửi đi, thử lại vẫn lỗi → thất bại luôn</li>
 * </ul>
 * Chạy an toàn trên virtual thread: {@code Thread.sleep} khi chờ không chiếm carrier thread.
 */
@Component
public class ThirdPartyClient {

    private static final Logger log = LoggerFactory.getLogger(ThirdPartyClient.class);
    private static final ParameterizedTypeReference<List<VerifyResult>> RESULT_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final ImportProperties.Api config;

    public ThirdPartyClient(ImportProperties properties) {
        this.config = properties.api();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(config.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public record VerifyItem(String externalId, String fullName, String email, String phone, String country) {
    }

    public record VerifyResult(String externalId, boolean verified, int riskScore) {
    }

    /**
     * @return kết quả theo đúng thứ tự {@code items}
     * @throws ThirdPartyException khi hết lượt thử hoặc lỗi không nên thử lại
     */
    public List<VerifyResult> verifyBulk(List<VerifyItem> items) throws InterruptedException {
        int attempt = 0;
        int rateLimitWaits = 0;
        while (true) {
            try {
                List<VerifyResult> results = restClient.post()
                        .uri("/v1/verify/bulk")
                        .body(items)
                        .retrieve()
                        .body(RESULT_LIST);
                checkMatches(items, results);
                return results;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) {
                    throw new ThirdPartyException("Rejected by third party: " + e.getStatusCode(), e);
                }
                if (++rateLimitWaits > config.maxRateLimitWaits()) {
                    throw new ThirdPartyException("Still rate limited after " + config.maxRateLimitWaits() + " waits", e);
                }
                Duration wait = retryAfter(e.getResponseHeaders());
                log.debug("Rate limited (429), waiting {} ms before resending", wait.toMillis());
                Thread.sleep(wait);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                // 5xx, timeout, connection refused: lỗi tạm thời → thử lại
                if (++attempt >= config.maxAttempts()) {
                    throw new ThirdPartyException("Failed after " + attempt + " attempts: " + e.getMessage(), e);
                }
                Duration backoff = config.initialBackoff().multipliedBy(1L << (attempt - 1));
                log.warn("Third-party call failed (attempt {}/{}): {} — retrying in {} ms",
                        attempt, config.maxAttempts(), e.getMessage(), backoff.toMillis());
                Thread.sleep(backoff);
            }
        }
    }

    public int batchSize() {
        return config.batchSize();
    }

    /** Header Retry-After tính bằng giây; thiếu hoặc sai định dạng thì chờ 1 giây. */
    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(1);
        }
    }

    /** API trả thiếu / sai thứ tự thì không thể ghép kết quả với dòng nào → coi như lỗi, không ghi bừa. */
    private static void checkMatches(List<VerifyItem> items, List<VerifyResult> results) {
        if (results == null || results.size() != items.size()) {
            throw new ThirdPartyException("Expected " + items.size() + " results but got "
                    + (results == null ? "none" : results.size()), null);
        }
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).externalId().equals(results.get(i).externalId())) {
                throw new ThirdPartyException("Result " + i + " is for " + results.get(i).externalId()
                        + " but expected " + items.get(i).externalId(), null);
            }
        }
    }
}
