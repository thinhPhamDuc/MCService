package com.app.importservice.ingest;

import com.app.importservice.TestCsv;
import com.app.importservice.TestcontainersConfiguration;
import com.app.importservice.config.ImportProperties;
import com.app.importservice.job.ImportJob;
import com.app.importservice.job.ImportJobRepository;
import com.app.importservice.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Chạy ingest đồng bộ ({@link IngestService#run}) trên MySQL thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IngestServiceTest {

    @Autowired
    private IngestService ingestService;
    @Autowired
    private ImportJobRepository jobs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportRowRepository rows;

    @TempDir
    Path dir;

    @Test
    void ingestsAllRowsInChunksAndMarksInvalidOnes() throws Exception {
        // 2503 dòng, chunk 1000 → 3 chunk, chunk cuối không đầy
        Path file = TestCsv.write(dir, "ok.csv", TestCsv.lines(2500, 3));
        long jobId = jobs.create("ok.csv");

        ingestService.run(jobId, file);

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.totalRows()).isEqualTo(2503);
        assertThat(job.invalidRows()).isEqualTo(3);
        assertThat(job.startedAt()).isNotNull();
        assertThat(countRows(jobId, "NEW")).isEqualTo(2500);
        assertThat(countRows(jobId, "FAILED")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT MIN(row_no) FROM import_row WHERE job_id = ?", Integer.class, jobId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MAX(row_no) FROM import_row WHERE job_id = ?", Integer.class, jobId))
                .isEqualTo(2503);
        assertThat(file).doesNotExist();
    }

    @Test
    void quotedCommaStaysInOneColumn() throws Exception {
        Path file = TestCsv.write(dir, "quoted.csv", TestCsv.lines(1, 0));
        long jobId = jobs.create("quoted.csv");

        ingestService.run(jobId, file);

        assertThat(jdbc.queryForObject("SELECT address FROM import_row WHERE job_id = ?", String.class, jobId))
                .isEqualTo("12 Nguyen Trai, Q1");
    }

    @Test
    void invalidRowKeepsErrorReason() throws Exception {
        Path file = TestCsv.write(dir, "bad.csv", TestCsv.lines(0, 1));
        long jobId = jobs.create("bad.csv");

        ingestService.run(jobId, file);

        assertThat(jdbc.queryForObject("SELECT error FROM import_row WHERE job_id = ?", String.class, jobId))
                .isEqualTo("email is invalid");
        // Không còn dòng hợp lệ nào → job kết thúc luôn
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void wrongHeaderFailsWholeJobAndKeepsNoRows() throws Exception {
        Path file = TestCsv.write(dir, "header.csv", List.of("id,name,email", TestCsv.validLine(1)));
        long jobId = jobs.create("header.csv");

        ingestService.run(jobId, file);

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("Invalid CSV header");
        assertThat(countRows(jobId, "NEW") + countRows(jobId, "FAILED")).isZero();
        assertThat(file).exists(); // giữ file gốc để điều tra
    }

    @Test
    void fileSavedByExcelWithBomIsAccepted() throws Exception {
        Path file = dir.resolve("bom.csv");
        Files.writeString(file, "﻿" + String.join("\n", TestCsv.lines(2, 0)), StandardCharsets.UTF_8);
        long jobId = jobs.create("bom.csv");

        ingestService.run(jobId, file);

        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.PROCESSING);
        assertThat(countRows(jobId, "NEW")).isEqualTo(2);
    }

    @Test
    void headerOnlyFileCompletesImmediately() throws Exception {
        Path file = TestCsv.write(dir, "empty.csv", TestCsv.lines(0, 0));
        long jobId = jobs.create("empty.csv");

        ingestService.run(jobId, file);

        ImportJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.totalRows()).isZero();
    }

    @Test
    void staleDetectionLooksAtHeartbeatNotStartTime() {
        long alive = jobs.create("alive.csv");
        long dead = jobs.create("dead.csv");
        jobs.markIngesting(alive);
        jobs.markIngesting(dead);
        // cả 2 bắt đầu 1 giờ trước; "alive" vẫn có nhịp tim vừa xong, "dead" im lặng 5 phút
        jdbc.update("UPDATE import_job SET started_at = NOW(3) - INTERVAL 1 HOUR WHERE id IN (?, ?)", alive, dead);
        jdbc.update("UPDATE import_job SET heartbeat_at = NOW(3) - INTERVAL 5 MINUTE WHERE id = ?", dead);
        jobs.heartbeat(alive);

        assertThat(jobs.findStaleIngesting(Duration.ofMinutes(2))).contains(dead).doesNotContain(alive);
    }

    @Test
    void oldQueuedJobIsFoundAsStale() {
        long old = jobs.create("old.csv");
        long fresh = jobs.create("fresh.csv");
        jdbc.update("UPDATE import_job SET created_at = NOW(3) - INTERVAL 1 HOUR WHERE id = ?", old);

        assertThat(jobs.findStaleQueued(Duration.ofMinutes(30))).contains(old).doesNotContain(fresh);
    }

    @Test
    void shutdownFailsJobsStillWaitingInQueue() throws Exception {
        var config = new ImportProperties.Ingest(6, 1000, 10, 1, Duration.ofSeconds(10), Duration.ofMinutes(2),
                Duration.ofMinutes(30));
        IngestService service = new IngestService(jobs, rows, new ImportProperties(dir, config, null, null));
        // 1 file ingest cùng lúc: file đầu chạy, 2 file sau nằm chờ trong hàng đợi
        long first = jobs.create("q1.csv");
        long second = jobs.create("q2.csv");
        long third = jobs.create("q3.csv");
        service.submit(first, TestCsv.write(dir, "q1.csv", TestCsv.lines(20_000, 0)));
        service.submit(second, TestCsv.write(dir, "q2.csv", TestCsv.lines(10, 0)));
        service.submit(third, TestCsv.write(dir, "q3.csv", TestCsv.lines(10, 0)));

        service.shutdown();

        ImportJob job = jobs.findById(third).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("Service stopped before this file was processed");
    }

    @Test
    void ingestRecordsHeartbeat() throws Exception {
        Path file = TestCsv.write(dir, "hb.csv", TestCsv.lines(10, 0));
        long jobId = jobs.create("hb.csv");

        ingestService.run(jobId, file);

        assertThat(jobs.findById(jobId).orElseThrow().heartbeatAt()).isNotNull();
    }

    private int countRows(long jobId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM import_row WHERE job_id = ? AND status = ?",
                Integer.class, jobId, status);
    }
}
