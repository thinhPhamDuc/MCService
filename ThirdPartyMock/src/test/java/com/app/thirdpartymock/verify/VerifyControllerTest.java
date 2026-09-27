package com.app.thirdpartymock.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifyControllerTest {

    private static final VerifyController.VerifyRequest ALICE =
            new VerifyController.VerifyRequest("C-1", "Alice", "alice@example.com", "0900000001", "VN");

    private ChaosSettings chaos;
    private VerifyController controller;

    @BeforeEach
    void setUp() {
        chaos = new ChaosSettings(0, 0.0, 0);
        controller = new VerifyController(chaos, new RateLimiter(), 100);
    }

    @Test
    void returnsOneResultPerItemInSameOrder() throws Exception {
        var bob = new VerifyController.VerifyRequest("C-2", "Bob", "bob@example.com", "0900000002", "VN");

        ResponseEntity<List<VerifyController.VerifyResult>> response = controller.verifyBulk(List.of(ALICE, bob));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(VerifyController.VerifyResult::externalId)
                .containsExactly("C-1", "C-2");
    }

    @Test
    void sameInputGivesSameResult() {
        assertThat(VerifyController.verify(ALICE)).isEqualTo(VerifyController.verify(ALICE));
    }

    @Test
    void invalidEmailIsNotVerified() {
        var noEmail = new VerifyController.VerifyRequest("C-3", "Carol", "not-an-email", null, "VN");

        assertThat(VerifyController.verify(noEmail).verified()).isFalse();
    }

    @Test
    void batchOverLimitIsRejected() {
        var tooMany = Collections.nCopies(101, ALICE);

        assertThatThrownBy(() -> controller.verifyBulk(tooMany))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Batch too large");
    }

    @Test
    void chaosErrorRateOneAlwaysFails() {
        chaos.setErrorRate(1.0);

        assertThatThrownBy(() -> controller.verifyBulk(List.of(ALICE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overRateLimitReturns429WithRetryAfter() throws Exception {
        chaos.setRateLimitPerSecond(1);
        controller = new VerifyController(chaos, new RateLimiter(() -> 5_000L), 100);

        assertThat(controller.verifyBulk(List.of(ALICE)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> rejected = controller.verifyBulk(List.of(ALICE));

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void rateLimitResetsInNextSecond() {
        long[] now = {5_000L};
        RateLimiter limiter = new RateLimiter(() -> now[0]);

        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
        now[0] = 6_000L;
        assertThat(limiter.tryAcquire(1)).isTrue();
    }
}
