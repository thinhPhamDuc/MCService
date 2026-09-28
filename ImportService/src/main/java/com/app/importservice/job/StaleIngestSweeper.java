package com.app.importservice.job;

import com.app.importservice.config.ImportProperties;
import com.app.importservice.ingest.ImportRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pod chết giữa lúc ingest → job kẹt ở INGESTING mãi mãi (và không còn ghi nhịp tim). Định kỳ đánh dấu các job này FAILED
 * và xoá dữ liệu dở dang; người dùng upload lại (ingest chỉ mất vài chục giây).
 */
@Component
public class StaleIngestSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleIngestSweeper.class);

    private final ImportJobRepository jobs;
    private final ImportRowRepository rows;
    private final ImportProperties.Ingest config;

    public StaleIngestSweeper(ImportJobRepository jobs, ImportRowRepository rows, ImportProperties properties) {
        this.jobs = jobs;
        this.rows = rows;
        this.config = properties.ingest();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        for (long jobId : jobs.findStaleQueued(config.queuedTimeout())) {
            if (jobs.failIfStillQueued(jobId, "Still queued after " + config.queuedTimeout()
                    + " (service probably restarted and lost its queue). Please upload the file again.")) {
                log.warn("Marked stale queued job as FAILED jobId={}", jobId);
            }
        }
        for (long jobId : jobs.findStaleIngesting(config.staleAfter())) {
            if (jobs.failIfStillIngesting(jobId, "Ingest interrupted (no heartbeat for " + config.staleAfter()
                    + ", service probably restarted). Please upload the file again.")) {
                rows.deleteByJob(jobId);
                log.warn("Marked stale ingest job as FAILED jobId={}", jobId);
            }
        }
    }
}
