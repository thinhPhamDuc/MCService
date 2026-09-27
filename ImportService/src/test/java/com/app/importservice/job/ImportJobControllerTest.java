package com.app.importservice.job;

import com.app.importservice.TestCsv;
import com.app.importservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gọi HTTP thật vào service: upload → 202 → ingest chạy nền → GET thấy job đổi trạng thái. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportJobControllerTest {

    @Value("${local.server.port}")
    private int port;

    @TempDir
    Path dir;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadReturns202AndJobIsIngestedInBackground() throws Exception {
        Path file = TestCsv.write(dir, "customers.csv", TestCsv.lines(1500, 2));
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new FileSystemResource(file));

        ResponseEntity<Map> response = client.post().uri("/imports")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long jobId = ((Number) response.getBody().get("jobId")).longValue();
        assertThat(response.getHeaders().getLocation()).hasToString("/imports/" + jobId);

        Map<String, Object> job = waitUntilStatusNot(jobId, "QUEUED", "INGESTING");
        assertThat(job.get("status")).isEqualTo("PROCESSING");
        assertThat(job.get("fileName")).isEqualTo("customers.csv");
        assertThat(job.get("totalRows")).isEqualTo(1502);
        assertThat(job.get("invalidRows")).isEqualTo(2);
    }

    @Test
    void unknownJobReturns404() {
        assertThatThrownBy(() -> client.get().uri("/imports/999999").retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitUntilStatusNot(long jobId, String... pending) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            Map<String, Object> job = client.get().uri("/imports/" + jobId).retrieve().body(Map.class);
            if (!java.util.List.of(pending).contains(job.get("status")) || System.currentTimeMillis() > deadline) {
                return job;
            }
            Thread.sleep(100);
        }
    }
}
