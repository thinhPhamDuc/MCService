package com.app.importservice.client;

import com.app.importservice.StubThirdParty;
import com.app.importservice.StubThirdParty.Reply;
import com.app.importservice.config.ImportProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThirdPartyClientTest {

    private static final List<ThirdPartyClient.VerifyItem> ITEMS = List.of(
            new ThirdPartyClient.VerifyItem("C-1", "Alice", "alice@example.com", null, "VN"),
            new ThirdPartyClient.VerifyItem("C-2", "Bob", "bob@example.com", null, "VN"));

    private StubThirdParty stub;
    private ThirdPartyClient client;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubThirdParty();
        var api = new ImportProperties.Api(stub.baseUrl(), 100, 16, 3, Duration.ofMillis(1), 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        client = new ThirdPartyClient(new ImportProperties(null, null, null, api));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void returnsResultsInOrder() throws Exception {
        List<ThirdPartyClient.VerifyResult> results = client.verifyBulk(ITEMS);

        assertThat(results).extracting(ThirdPartyClient.VerifyResult::externalId).containsExactly("C-1", "C-2");
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void retriesServerErrorThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        stub.respondWith(ids -> calls.incrementAndGet() < 3 ? Reply.status(500) : Reply.ok(ids));

        assertThat(client.verifyBulk(ITEMS)).hasSize(2);
        assertThat(stub.requestCount()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        stub.respondWith(ids -> Reply.status(503));

        assertThatThrownBy(() -> client.verifyBulk(ITEMS))
                .isInstanceOf(ThirdPartyException.class)
                .hasMessageContaining("Failed after 3 attempts");
        assertThat(stub.requestCount()).isEqualTo(3);
    }

    @Test
    void clientErrorIsNotRetried() {
        stub.respondWith(ids -> Reply.status(400));

        assertThatThrownBy(() -> client.verifyBulk(ITEMS))
                .isInstanceOf(ThirdPartyException.class)
                .hasMessageContaining("Rejected");
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void rateLimitWaitsRetryAfterAndDoesNotUseAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 1 lần 429 + 2 lần 500 + thành công: 429 không bị tính vào maxAttempts = 3
        stub.respondWith(ids -> switch (calls.incrementAndGet()) {
            case 1 -> Reply.tooManyRequests("1");
            case 2, 3 -> Reply.status(500);
            default -> Reply.ok(ids);
        });

        long start = System.nanoTime();
        assertThat(client.verifyBulk(ITEMS)).hasSize(2);

        assertThat(stub.requestCount()).isEqualTo(4);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void givesUpWhenRateLimitedTooManyTimes() {
        stub.respondWith(ids -> Reply.tooManyRequests("1"));

        assertThatThrownBy(() -> client.verifyBulk(ITEMS))
                .isInstanceOf(ThirdPartyException.class)
                .hasMessageContaining("Still rate limited");
        assertThat(stub.requestCount()).isEqualTo(3); // maxRateLimitWaits = 2 → lần thứ 3 thì bỏ
    }

    @Test
    void mismatchedResponseIsRejected() {
        stub.respondWith(ids -> Reply.ok(List.of("C-2", "C-1")));

        assertThatThrownBy(() -> client.verifyBulk(ITEMS))
                .isInstanceOf(ThirdPartyException.class)
                .hasMessageContaining("expected C-1");
    }
}
