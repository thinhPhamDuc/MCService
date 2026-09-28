package com.app.importservice.ingest;

import com.app.importservice.config.ImportProperties;
import com.app.importservice.job.ImportJobRepository;
import com.app.importservice.job.JobStatus;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Giai đoạn 1 — INGEST: đọc file CSV (streaming) → kiểm tra → ghi bảng staging import_row theo chunk.
 *
 * <pre>
 * 1 reader ──chunk──▶ [tối đa writers + queueCapacity chunk đang chờ/ghi] ──▶ W writer ──batch insert──▶ MySQL
 * </pre>
 *
 * Số thread cố định theo cấu hình, không theo số dòng: file 1 triệu hay 10 triệu dòng vẫn 1 reader + W writer,
 * RAM tối đa ≈ (writers + queueCapacity) × chunkSize dòng. Writer chậm → Semaphore hết chỗ → reader tự dừng đọc.
 * Dùng platform thread (không phải virtual thread): việc ở đây tốn CPU (parse) và bị giới hạn bởi DB, không phải chờ mạng.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                  // dòng đầu là header
            .setSkipHeaderRecord(true)
            .get();

    private final ImportJobRepository jobs;
    private final ImportRowRepository rows;
    private final ImportProperties.Ingest config;
    /** Giới hạn số file ingest cùng lúc; job còn lại nằm trong hàng đợi của executor (trạng thái QUEUED). */
    private final ExecutorService jobExecutor;

    public IngestService(ImportJobRepository jobs, ImportRowRepository rows, ImportProperties properties) {
        this.jobs = jobs;
        this.rows = rows;
        this.config = properties.ingest();
        this.jobExecutor = Executors.newFixedThreadPool(config.maxConcurrentJobs(),
                Thread.ofPlatform().name("ingest-job-", 0).factory());
    }

    /** Nhận job vào hàng đợi, trả về ngay (HTTP request không phải chờ đọc hết file). */
    public void submit(long jobId, Path file) {
        jobExecutor.execute(() -> run(jobId, file));
    }

    /** Chạy ingest 1 job từ đầu tới cuối và cập nhật trạng thái job. Không ném exception ra ngoài. */
    public void run(long jobId, Path file) {
        MDC.put("jobId", String.valueOf(jobId));
        try {
            jobs.markIngesting(jobId);
            long start = System.nanoTime();
            Counts counts = ingest(jobId, file);
            long millis = (System.nanoTime() - start) / 1_000_000;

            jobs.markIngested(jobId, counts.total(), counts.invalid());
            if (counts.total() == counts.invalid()) {
                // Không còn dòng hợp lệ nào cho worker xử lý → job kết thúc luôn
                jobs.markFinished(jobId, counts.invalid() > 0 ? JobStatus.COMPLETED_WITH_ERRORS : JobStatus.COMPLETED);
            }
            Files.deleteIfExists(file);
            log.info("Ingest done jobId={} rows={} invalid={} took={}ms ({} rows/s)", jobId, counts.total(),
                    counts.invalid(), millis, millis == 0 ? counts.total() : counts.total() * 1000L / millis);
        } catch (Exception e) {
            log.error("Ingest failed jobId={}: {}", jobId, e.getMessage(), e);
            // Bỏ dữ liệu dở dang để worker không xử lý 1 nửa file; file gốc giữ lại để điều tra
            rows.deleteByJob(jobId);
            jobs.markFailed(jobId, "Ingest failed: " + e.getMessage());
        } finally {
            MDC.remove("jobId");
        }
    }

    private Counts ingest(long jobId, Path file) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var inFlight = new Semaphore(config.writers() + config.queueCapacity());
        int total = 0;
        int invalid = 0;

        // Thứ tự đóng ngược thứ tự mở: parser → reader → writers. writers.close() chờ các chunk đang ghi xong.
        try (ExecutorService writers = Executors.newFixedThreadPool(config.writers(),
                     Thread.ofPlatform().name("ingest-writer-" + jobId + "-", 0).factory());
             Reader reader = openUtf8SkippingBom(file);
             CSVParser parser = FORMAT.parse(reader)) {

            CustomerCsv.checkHeader(parser.getHeaderNames());
            long nextHeartbeat = System.nanoTime() + config.heartbeatInterval().toNanos();
            List<StagedRow> chunk = new ArrayList<>(config.chunkSize());
            for (CSVRecord record : parser) {
                StagedRow row = CustomerCsv.toStagedRow(++total, record.toList());
                if (!row.valid()) {
                    invalid++;
                }
                chunk.add(row);
                if (chunk.size() == config.chunkSize()) {
                    submitChunk(writers, inFlight, failure, jobId, chunk);
                    chunk = new ArrayList<>(config.chunkSize());
                    // Nhịp tim theo THỜI GIAN (không phải mỗi chunk): 1 triệu dòng chỉ ~1 lần ghi thay vì 1000 lần.
                    // Writer bị treo → reader kẹt ở Semaphore → hết nhịp tim → sweeper coi là chết: đúng ý nghĩa.
                    if (System.nanoTime() >= nextHeartbeat) {
                        jobs.heartbeat(jobId);
                        nextHeartbeat = System.nanoTime() + config.heartbeatInterval().toNanos();
                    }
                }
            }
            if (!chunk.isEmpty()) {
                submitChunk(writers, inFlight, failure, jobId, chunk);
            }
        }
        throwIfFailed(failure);
        return new Counts(total, invalid);
    }

    private void submitChunk(ExecutorService writers, Semaphore inFlight, AtomicReference<Throwable> failure,
                             long jobId, List<StagedRow> chunk) throws Exception {
        throwIfFailed(failure);
        inFlight.acquire(); // đủ chunk đang chờ → reader đứng đây, không đọc thêm vào RAM
        writers.execute(() -> {
            MDC.put("jobId", String.valueOf(jobId));
            try {
                if (failure.get() == null) {
                    rows.insertChunk(jobId, chunk);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                inFlight.release();
                MDC.remove("jobId");
            }
        });
    }

    private static void throwIfFailed(AtomicReference<Throwable> failure) throws Exception {
        Throwable t = failure.get();
        if (t != null) {
            throw new IllegalStateException("Writing staging rows failed: " + t.getMessage(), t);
        }
    }

    /** File lưu từ Excel thường có BOM (U+FEFF) ở đầu → header thành "﻿external_id" nếu không bỏ đi. */
    private static Reader openUtf8SkippingBom(Path file) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
        return reader;
    }

    @PreDestroy
    void shutdown() {
        jobExecutor.shutdownNow();
    }

    private record Counts(int total, int invalid) {
    }
}
