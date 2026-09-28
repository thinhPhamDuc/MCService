package com.app.importservice.job;

import com.app.importservice.TestCsv;
import com.app.importservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gọi HTTP thật vào service: upload → 202 → ingest chạy nền → GET thấy job đổi trạng thái. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportJobControllerTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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
    @SuppressWarnings("unchecked")
    void progressCountsInvalidRowsAsDone() throws Exception {
        long jobId = uploadAndWait(TestCsv.lines(8, 2));

        Map<String, Object> job = client.get().uri("/imports/" + jobId).retrieve().body(Map.class);

        // worker tắt khi test: mới có 2 dòng sai dữ liệu "xong" / 10 dòng
        assertThat(job.get("progressPercent")).isEqualTo(20.0);
        assertThat(job.get("durationSeconds")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorsArePagedWithKeyset() throws Exception {
        long jobId = uploadAndWait(TestCsv.lines(5, 3)); // dòng 6, 7, 8 sai email

        Map<String, Object> page1 = client.get().uri("/imports/" + jobId + "/errors?limit=2").retrieve().body(Map.class);
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) page1.get("items");
        assertThat(items1).extracting(i -> i.get("rowNo")).containsExactly(6, 7);
        assertThat(items1.getFirst()).containsEntry("reason", "INVALID").containsEntry("error", "email is invalid");
        assertThat(page1.get("nextAfterRowNo")).isEqualTo(7);

        Map<String, Object> page2 = client.get().uri("/imports/" + jobId + "/errors?limit=2&afterRowNo=7")
                .retrieve().body(Map.class);
        assertThat((List<Map<String, Object>>) page2.get("items")).extracting(i -> i.get("rowNo")).containsExactly(8);
        assertThat(page2.get("nextAfterRowNo")).isNull();
    }

    @Test
    void retryFailedOnRunningJobReturns409() throws Exception {
        long jobId = uploadAndWait(TestCsv.lines(5, 0)); // PROCESSING (worker tắt khi test)

        assertThatThrownBy(() -> client.post().uri("/imports/" + jobId + "/retry-failed").retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryFailedResetsOnlyApiFailures() throws Exception {
        long jobId = uploadAndWait(TestCsv.lines(5, 1)); // dòng 6 sai dữ liệu
        // giả lập giai đoạn 2 đã chạy xong: dòng 1-3 DONE, dòng 4-5 lỗi API
        jdbc.update("UPDATE import_row SET status = 'DONE', attempts = 1 WHERE job_id = ? AND row_no <= 3", jobId);
        jdbc.update("UPDATE import_row SET status = 'FAILED', attempts = 1, error = 'Third-party API: boom' "
                + "WHERE job_id = ? AND row_no IN (4, 5)", jobId);
        jdbc.update("UPDATE import_job SET status = 'COMPLETED_WITH_ERRORS', processed_rows = 5, failed_rows = 2, "
                + "finished_at = NOW(3) WHERE id = ?", jobId);

        Map<String, Object> result = client.post().uri("/imports/" + jobId + "/retry-failed").retrieve().body(Map.class);

        assertThat(result).containsEntry("retriedRows", 2).containsEntry("status", "PROCESSING");
        Map<String, Object> job = client.get().uri("/imports/" + jobId).retrieve().body(Map.class);
        assertThat(job).containsEntry("status", "PROCESSING").containsEntry("processedRows", 3).containsEntry("failedRows", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_row WHERE job_id = ? AND status = 'NEW'",
                Integer.class, jobId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM import_row WHERE job_id = ? AND row_no = 6",
                String.class, jobId)).isEqualTo("FAILED");
    }

    @Test
    void unknownJobReturns404() {
        assertThatThrownBy(() -> client.get().uri("/imports/999999").retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private long uploadAndWait(List<String> lines) throws Exception {
        Path file = TestCsv.write(dir, "f-" + System.nanoTime() + ".csv", lines);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new FileSystemResource(file));
        Map<?, ?> body = client.post().uri("/imports").contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().body(Map.class);
        long jobId = ((Number) body.get("jobId")).longValue();
        waitUntilStatusNot(jobId, "QUEUED", "INGESTING");
        return jobId;
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
