package com.app.importservice.process;

import com.app.importservice.client.ThirdPartyClient;
import com.app.importservice.client.ThirdPartyClient.VerifyItem;
import com.app.importservice.client.ThirdPartyClient.VerifyResult;
import com.app.importservice.config.ImportProperties;
import com.app.importservice.ingest.ImportRowRepository;
import com.app.importservice.ingest.ImportRowRepository.ClaimedRow;
import com.app.importservice.job.ImportJobRepository;
import com.app.importservice.job.JobStatus;
import com.app.importservice.process.CustomerRepository.VerifiedRow;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Giai đoạn 2 — PROCESS: worker lấy dòng NEW → gọi bulk API → ghi bảng customer.
 *
 * <pre>
 * K worker (virtual thread), mỗi worker lặp:
 *   1. tx ngắn: claim 500 dòng NEW (FOR UPDATE SKIP LOCKED) → PROCESSING, commit
 *   2. KHÔNG giữ transaction: chia 500 dòng thành 5 request × 100, mỗi request 1 virtual thread,
 *      phải lấy 1 "thẻ" của Semaphore(api.max-concurrent) mới được gọi
 *   3. tx ngắn: INSERT customer + import_row → DONE / FAILED + cộng tiến độ job, commit
 *   4. hết dòng chờ → job COMPLETED / COMPLETED_WITH_ERRORS
 * </pre>
 *
 * Virtual thread vì gần như toàn bộ thời gian là chờ mạng. Giới hạn thật là Semaphore (bên thứ ba chịu được bao nhiêu
 * request cùng lúc), không phải số thread — xem docs/csv-import-design.md mục 3.
 */
@Service
public class ProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    private final ImportJobRepository jobs;
    private final ImportRowRepository rows;
    private final CustomerRepository customers;
    private final ThirdPartyClient client;
    private final TransactionTemplate tx;
    private final ImportProperties.Process config;
    /** Chung cho mọi worker: tổng số request đang gọi bên thứ ba không vượt quá api.max-concurrent. */
    private final Semaphore apiPermits;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public ProcessService(ImportJobRepository jobs, ImportRowRepository rows, CustomerRepository customers,
                          ThirdPartyClient client, TransactionTemplate tx, ImportProperties properties) {
        this.jobs = jobs;
        this.rows = rows;
        this.customers = customers;
        this.client = client;
        this.tx = tx;
        this.config = properties.process();
        this.apiPermits = new Semaphore(properties.api().maxConcurrent());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!config.enabled()) {
            log.info("Process workers disabled (import.process.enabled=false)");
            return;
        }
        running = true;
        for (int i = 0; i < config.workers(); i++) {
            workers.add(Thread.ofVirtual().name("process-worker-" + i).start(this::loop));
        }
        log.info("Started {} process workers, api.max-concurrent={}", config.workers(), apiPermits.availablePermits());
    }

    private void loop() {
        while (running) {
            try {
                if (!processNextChunk()) {
                    Thread.sleep(config.pollInterval()); // không có việc → ngủ rồi hỏi lại
                }
            } catch (InterruptedException e) {
                return; // đang tắt service
            } catch (Exception e) {
                // Lỗi DB tạm thời...: dòng đã claim vẫn PROCESSING, StaleClaimSweeper sẽ trả về NEW sau staleAfter
                log.error("Process worker error: {}", e.getMessage(), e);
                try {
                    Thread.sleep(config.pollInterval());
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Xử lý 1 chunk của job cũ nhất còn việc. Public để test gọi trực tiếp, không cần chờ worker nền.
     *
     * @return false nếu không còn việc gì để làm
     */
    public boolean processNextChunk() throws InterruptedException {
        Optional<Long> next = jobs.findJobWithNewRows();
        if (next.isEmpty()) {
            return false;
        }
        long jobId = next.get();
        MDC.put("jobId", String.valueOf(jobId));
        try {
            List<ClaimedRow> claimed = rows.claim(jobId, config.claimSize());
            if (claimed.isEmpty()) {
                return true; // worker khác vừa lấy hết; vòng sau sẽ tìm job khác
            }

            List<BatchOutcome> outcomes = callApiInParallel(jobId, claimed);

            List<VerifiedRow> verified = new ArrayList<>();
            List<BatchOutcome> failedBatches = new ArrayList<>();
            for (BatchOutcome outcome : outcomes) {
                if (outcome.error() == null) {
                    for (int i = 0; i < outcome.batch().size(); i++) {
                        verified.add(new VerifiedRow(outcome.batch().get(i), outcome.results().get(i)));
                    }
                } else {
                    failedBatches.add(outcome);
                }
            }
            int failedCount = claimed.size() - verified.size();

            tx.executeWithoutResult(status -> {
                customers.insert(jobId, verified);
                rows.markDone(jobId, verified.stream().map(v -> v.row().rowNo()).toList());
                for (BatchOutcome failed : failedBatches) {
                    rows.markFailed(jobId, failed.batch().stream().map(ClaimedRow::rowNo).toList(), failed.error());
                }
                jobs.addProgress(jobId, claimed.size(), failedCount);
            });
            log.debug("Chunk done jobId={} rows={} failed={}", jobId, claimed.size(), failedCount);

            completeIfDone(jobId);
            return true;
        } finally {
            MDC.remove("jobId");
        }
    }

    /**
     * Hết dòng NEW / PROCESSING → kết thúc job. Mỗi worker kiểm tra SAU khi commit chunk của mình,
     * nên worker commit cuối cùng chắc chắn thấy mọi chunk đã xong.
     */
    public void completeIfDone(long jobId) {
        if (rows.hasPendingRows(jobId)) {
            return;
        }
        JobStatus status = rows.hasFailedRows(jobId) ? JobStatus.COMPLETED_WITH_ERRORS : JobStatus.COMPLETED;
        if (jobs.finishIfProcessing(jobId, status)) {
            jobs.findById(jobId).ifPresent(job -> log.info(
                    "Import finished jobId={} status={} total={} processed={} failed={} invalid={}",
                    jobId, status, job.totalRows(), job.processedRows(), job.failedRows(), job.invalidRows()));
        }
    }

    private List<BatchOutcome> callApiInParallel(long jobId, List<ClaimedRow> claimed) throws InterruptedException {
        List<Future<BatchOutcome>> futures = new ArrayList<>();
        // 1 virtual thread / 1 request; executor đóng lại = chờ mọi request xong
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 0; from < claimed.size(); from += client.batchSize()) {
                List<ClaimedRow> batch = claimed.subList(from, Math.min(from + client.batchSize(), claimed.size()));
                futures.add(executor.submit(() -> callApi(jobId, batch)));
            }
        }
        List<BatchOutcome> outcomes = new ArrayList<>(futures.size());
        for (Future<BatchOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (ExecutionException e) {
                // callApi đã tự bắt mọi lỗi; tới đây chỉ có thể là bị interrupt khi đang tắt service
                throw new InterruptedException("Interrupted while calling third party");
            }
        }
        return outcomes;
    }

    private BatchOutcome callApi(long jobId, List<ClaimedRow> batch) throws InterruptedException {
        MDC.put("jobId", String.valueOf(jobId)); // virtual thread mới không thừa hưởng MDC
        apiPermits.acquire(); // hết thẻ → chờ, không gửi thêm request cho bên thứ ba
        try {
            List<VerifyItem> items = batch.stream().map(ProcessService::toItem).toList();
            return new BatchOutcome(batch, client.verifyBulk(items), null);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Batch of {} rows failed: {}", batch.size(), e.getMessage());
            return new BatchOutcome(batch, null, "Third-party API: " + e.getMessage());
        } finally {
            apiPermits.release();
            MDC.remove("jobId");
        }
    }

    private static VerifyItem toItem(ClaimedRow row) {
        String[] v = row.values();
        return new VerifyItem(v[0], v[1], v[2], v[3], v[8]);
    }

    /** Dòng đang PROCESSING của worker bị dừng sẽ được StaleClaimSweeper trả về NEW sau staleAfter. */
    @PreDestroy
    synchronized void stop() throws InterruptedException {
        running = false;
        workers.forEach(Thread::interrupt);
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        workers.clear();
    }

    private record BatchOutcome(List<ClaimedRow> batch, List<VerifyResult> results, String error) {
    }
}
