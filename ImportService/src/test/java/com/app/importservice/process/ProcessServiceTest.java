package com.app.importservice.process;

import com.app.importservice.StubThirdParty;
import com.app.importservice.StubThirdParty.Reply;
import com.app.importservice.TestCsv;
import com.app.importservice.TestcontainersConfiguration;
import com.app.importservice.client.ThirdPartyClient;
import com.app.importservice.config.ImportProperties;
import com.app.importservice.ingest.ImportRowRepository;
import com.app.importservice.ingest.IngestService;
import com.app.importservice.job.ImportJob;
import com.app.importservice.job.ImportJobRepository;
import com.app.importservice.job.JobStatus;
import com.app.importservice.job.RetryFailedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giai đoạn 2 trên MySQL thật + API giả ({@link StubThirdParty}). Worker nền bị tắt khi test
 * (src/test/resources/config/application.properties); test tự gọi {@link ProcessService#processNextChunk()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ProcessServiceTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private ImportJobRepository jobs;
    @Autowired
    private ImportRowRepository rows;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RetryFailedService retryFailedService;

    @TempDir
    Path dir;

    private StubThirdParty stub;
    private ProcessService processService;

    @BeforeEach
    void setUp() throws Exception {
        // Các test dùng chung 1 MySQL: job PROCESSING do test ingest để lại sẽ bị processAll() xử lý ké
        // và làm lệch số request đếm được → đóng chúng lại trước mỗi test.
        jdbc.update("UPDATE import_job SET status = 'FAILED' WHERE status = 'PROCESSING'");
        stub = new StubThirdParty();
        var process = new ImportProperties.Process(false, 8, 500, Duration.ofMillis(100), Duration.ofMinutes(5));
        var api = new ImportProperties.Api(stub.baseUrl(), 100, 16, 3, Duration.ofMillis(1), 2,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        var properties = new ImportProperties(dir, null, process, api);
        processService = new ProcessService(jobs, rows, customers, new ThirdPartyClient(properties), tx, properties);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void processesAllRowsAndCompletesJob() throws Exception {
        long jobId = ingest(1203, 0); // 500 + 500 + 203 → 3 chunk; 13 request (5 + 5 + 3)

        processAll();

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.processedRows()).isEqualTo(1203);
        assertThat(job.failedRows()).isZero();
        assertThat(job.finishedAt()).isNotNull();
        assertThat(countCustomers(jobId)).isEqualTo(1203);
        assertThat(countRows(jobId, "DONE")).isEqualTo(1203);
        assertThat(stub.requestCount()).isEqualTo(13);
        // dữ liệu được chuyển kiểu + gắn kết quả từ API
        assertThat(jdbc.queryForObject("SELECT CONCAT(date_of_birth, '|', annual_income, '|', verified) FROM customer "
                + "WHERE job_id = ? AND row_no = 1", String.class, jobId)).isEqualTo("1990-01-15|120000.50|1");
    }

    @Test
    void invalidRowsFromIngestMakeJobCompletedWithErrors() throws Exception {
        long jobId = ingest(10, 2);

        processAll();

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.processedRows()).isEqualTo(10);
        assertThat(job.invalidRows()).isEqualTo(2);
        assertThat(countCustomers(jobId)).isEqualTo(10);
    }

    @Test
    void failedBatchMarksOnlyItsRowsFailed() throws Exception {
        long jobId = ingest(300, 0);
        // request nào chứa C-150 (batch 101..200) luôn lỗi 500 → hết 3 lần thử → 100 dòng FAILED
        stub.respondWith(ids -> ids.contains("C-150") ? Reply.status(500) : Reply.ok(ids));

        processAll();

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.processedRows()).isEqualTo(300);
        assertThat(job.failedRows()).isEqualTo(100);
        assertThat(countRows(jobId, "FAILED")).isEqualTo(100);
        assertThat(countCustomers(jobId)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT error FROM import_row WHERE job_id = ? AND row_no = 150",
                String.class, jobId)).contains("Failed after 3 attempts");
    }

    @Test
    void retryFailedReprocessesApiFailuresAfterOutageButNotInvalidRows() throws Exception {
        long jobId = ingest(300, 2);
        // sự cố: batch chứa C-150 lỗi hẳn → 100 dòng FAILED (API) + 2 dòng FAILED (sai dữ liệu)
        stub.respondWith(ids -> ids.contains("C-150") ? Reply.status(500) : Reply.ok(ids));
        processAll();
        assertThat(jobs.findById(jobId).orElseThrow().failedRows()).isEqualTo(100);

        stub.respondWith(Reply::ok); // bên thứ ba đã hết sự cố
        RetryFailedService.RetryResult result = retryFailedService.retryFailed(jobId);
        assertThat(result.retriedRows()).isEqualTo(100);
        assertThat(result.status()).isEqualTo(JobStatus.PROCESSING);
        processAll();

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS); // vẫn còn 2 dòng sai dữ liệu
        assertThat(job.processedRows()).isEqualTo(300);
        assertThat(job.failedRows()).isZero();
        assertThat(job.invalidRows()).isEqualTo(2);
        assertThat(countCustomers(jobId)).isEqualTo(300);
        assertThat(countRows(jobId, "FAILED")).isEqualTo(2);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameRow() throws Exception {
        long jobId = ingest(5000, 0);

        // 8 luồng cùng claim: nhờ SKIP LOCKED mỗi dòng chỉ được nhận đúng 1 lần
        List<Future<?>> futures = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    processAll();
                    return null;
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get();
        }

        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(countCustomers(jobId)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT MAX(attempts) FROM import_row WHERE job_id = ?", Integer.class, jobId))
                .isEqualTo(1);
        // Số request KHÔNG cố định (khi chạy song song, phần cuối có thể bị chia 250 + 250 → 3 + 3 request),
        // nhưng mỗi khách hàng phải được gửi sang API đúng 1 lần
        assertThat(stub.sentIds()).hasSize(5000).doesNotHaveDuplicates();
    }

    @Test
    void reprocessingSameRowsDoesNotDuplicateCustomers() throws Exception {
        long jobId = ingest(100, 0);
        processAll();
        // giả lập worker chết SAU khi ghi customer nhưng trước khi đánh DONE: dòng quay về NEW, job chưa xong
        jdbc.update("UPDATE import_row SET status = 'NEW' WHERE job_id = ?", jobId);
        jdbc.update("UPDATE import_job SET status = 'PROCESSING' WHERE id = ?", jobId);

        processAll();

        assertThat(countCustomers(jobId)).isEqualTo(100);
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void backgroundWorkersPickUpJobAndStopCleanly() throws Exception {
        var process = new ImportProperties.Process(true, 4, 500, Duration.ofMillis(50), Duration.ofMinutes(5));
        var api = new ImportProperties.Api(stub.baseUrl(), 100, 16, 3, Duration.ofMillis(1), 2,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        var properties = new ImportProperties(dir, null, process, api);
        var background = new ProcessService(jobs, rows, customers, new ThirdPartyClient(properties), tx, properties);
        background.start();
        try {
            long jobId = ingest(2000, 0);

            long deadline = System.currentTimeMillis() + 20_000;
            while (jobs.findById(jobId).orElseThrow().status() != JobStatus.COMPLETED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(countCustomers(jobId)).isEqualTo(2000);
        } finally {
            background.stop();
        }
    }

    @Test
    void staleClaimsAreReleasedBackToNew() throws Exception {
        long jobId = ingest(10, 0);
        rows.claim(jobId, 10);
        jdbc.update("UPDATE import_row SET claimed_at = NOW(3) - INTERVAL 10 MINUTE WHERE job_id = ?", jobId);

        int released = rows.releaseStaleClaims(Duration.ofMinutes(5));

        assertThat(released).isEqualTo(10);
        assertThat(countRows(jobId, "NEW")).isEqualTo(10);
    }

    @Test
    void recentClaimsAreNotReleased() throws Exception {
        long jobId = ingest(10, 0);
        rows.claim(jobId, 10);

        rows.releaseStaleClaims(Duration.ofMinutes(5));

        assertThat(countRows(jobId, "PROCESSING")).isEqualTo(10);
    }

    private long ingest(int valid, int invalid) throws Exception {
        Path file = TestCsv.write(dir, "job-" + System.nanoTime() + ".csv", TestCsv.lines(valid, invalid));
        long jobId = jobs.create(file.getFileName().toString());
        ingestService.run(jobId, file);
        return jobId;
    }

    /** Chạy tới khi không còn việc. */
    private void processAll() throws InterruptedException {
        while (processService.processNextChunk()) {
            // tiếp tục
        }
    }

    private int countCustomers(long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE job_id = ?", Integer.class, jobId);
    }

    private int countRows(long jobId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_row WHERE job_id = ? AND status = ?",
                Integer.class, jobId, status);
    }
}
