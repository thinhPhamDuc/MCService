package com.app.importservice;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * API bên thứ ba giả trong test (HTTP thật, cổng ngẫu nhiên). {@link #respondWith} quyết định trả gì
 * cho từng request dựa trên danh sách externalId gửi lên; mặc định trả verified cho mọi item.
 */
public class StubThirdParty implements AutoCloseable {

    public record Reply(int status, String body, String retryAfter) {

        public static Reply ok(List<String> externalIds) {
            String body = externalIds.stream()
                    .map(id -> "{\"externalId\":\"" + id + "\",\"verified\":true,\"riskScore\":" + Math.floorMod(id.hashCode(), 100) + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            return new Reply(200, body, null);
        }

        public static Reply status(int status) {
            return new Reply(status, "", null);
        }

        public static Reply tooManyRequests(String retryAfter) {
            return new Reply(429, "", retryAfter);
        }
    }

    private static final Pattern EXTERNAL_ID = Pattern.compile("\"externalId\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final Queue<String> sentIds = new ConcurrentLinkedQueue<>();
    private volatile Function<List<String>, Reply> handler = Reply::ok;

    public StubThirdParty() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/verify/bulk", exchange -> {
            requests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = EXTERNAL_ID.matcher(body);
            List<String> ids = m.results().map(r -> r.group(1)).toList();
            sentIds.addAll(ids);
            Reply reply = handler.apply(ids);
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().set("Retry-After", reply.retryAfter());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    public void respondWith(Function<List<String>, Reply> handler) {
        this.handler = handler;
    }

    /** Mọi externalId đã nhận được (kể cả các lần thử lại), theo thứ tự đến. */
    public List<String> sentIds() {
        return List.copyOf(sentIds);
    }

    public int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
