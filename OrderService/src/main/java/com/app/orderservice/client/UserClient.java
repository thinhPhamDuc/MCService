package com.app.orderservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class UserClient {

    private static final Logger log = LoggerFactory.getLogger(UserClient.class);

    private final RestClient restClient;

    public UserClient(@Qualifier("userRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public record UserInfo(Long userId, String username) {
    }

    /** Hỏi UserService xem token có hợp lệ không. */
    public UserInfo validate(String authorization) {
        if (authorization == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }
        try {
            return restClient.get()
                    .uri("/auth/validate")
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(UserInfo.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        } catch (RestClientException e) {
            // UserService chết / timeout / lỗi 5xx -> OrderService không thể xác thực -> trả 503
            log.error("Cannot reach UserService: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "UserService unavailable");
        }
    }
}
