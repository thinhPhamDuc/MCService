package com.app.importservice.process;

import com.app.importservice.config.ImportProperties;
import com.app.importservice.ingest.ImportRowRepository;
import com.app.importservice.job.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dọn dẹp định kỳ cho giai đoạn 2:
 * <ul>
 *   <li>Dòng PROCESSING quá {@code import.process.stale-after} (worker / pod chết giữa chừng) → trả về NEW</li>
 *   <li>Job PROCESSING đã hết việc nhưng chưa được đóng (pod chết ngay sau khi commit chunk cuối) → đóng lại</li>
 * </ul>
 */
@Component
public class StaleClaimSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleClaimSweeper.class);

    private final ImportRowRepository rows;
    private final ImportJobRepository jobs;
    private final ProcessService processService;
    private final ImportProperties.Process config;

    public StaleClaimSweeper(ImportRowRepository rows, ImportJobRepository jobs, ProcessService processService,
                             ImportProperties properties) {
        this.rows = rows;
        this.jobs = jobs;
        this.processService = processService;
        this.config = properties.process();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        int released = rows.releaseStaleClaims(config.staleAfter());
        if (released > 0) {
            log.warn("Released {} rows stuck in PROCESSING for more than {}", released, config.staleAfter());
        }
        jobs.findProcessing().forEach(processService::completeIfDone);
    }
}
