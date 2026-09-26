package com.app.orderservice.client;

import com.app.orderservice.common.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${services.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${services.read-timeout-ms}")
    private int readTimeoutMs;

    @Bean
    RestClient userRestClient(@Value("${services.user.url}") String baseUrl) {
        return build(baseUrl);
    }

    @Bean
    RestClient paymentRestClient(@Value("${services.payment.url}") String baseUrl) {
        return build(baseUrl);
    }

    private RestClient build(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(forwardRequestId())
                .build();
    }

    /** Chuyển tiếp X-Request-Id sang service được gọi để log các service nối được với nhau. */
    private static ClientHttpRequestInterceptor forwardRequestId() {
        return (request, body, execution) -> {
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) {
                request.getHeaders().set(RequestIdFilter.HEADER, requestId);
            }
            return execution.execute(request, body);
        };
    }
}
