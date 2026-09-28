package com.app.importservice.job;

import com.app.importservice.ingest.ImportRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chạy lại các dòng lỗi do API bên thứ ba (hết lượt thử, bên kia sập...) sau khi sự cố đã qua.
 * Dòng sai dữ liệu (lỗi lúc ingest) KHÔNG chạy lại: gửi lại vẫn sai — phải sửa file rồi upload lại.
 */
@Service
public class RetryFailedService {

    private static final Logger log = LoggerFactory.getLogger(RetryFailedService.class);

    private final ImportJobRepository jobs;
    private final ImportRowRepository rows;
    private final TransactionTemplate tx;

    public RetryFailedService(ImportJobRepository jobs, ImportRowRepository rows, TransactionTemplate tx) {
        this.jobs = jobs;
        this.rows = rows;
        this.tx = tx;
    }

    public record RetryResult(long jobId, int retriedRows, JobStatus status) {
    }

    /**
     * 1 transaction: khoá job → kiểm tra trạng thái → dòng lỗi API về NEW → job về PROCESSING.
     * Worker nền thấy dòng NEW sẽ tự xử lý như bình thường.
     */
    public RetryResult retryFailed(long jobId) {
        return tx.execute(status -> {
            JobStatus current = jobs.lockStatus(jobId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found"));
            if (current != JobStatus.COMPLETED_WITH_ERRORS) {
                // Đang chạy thì dòng lỗi có thể còn tăng; COMPLETED thì không có gì để chạy lại; FAILED thì phải upload lại
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Job is " + current + "; only COMPLETED_WITH_ERRORS jobs can be retried");
            }
            int retried = rows.resetApiFailures(jobId);
            if (retried == 0) {
                return new RetryResult(jobId, 0, current); // chỉ có dòng sai dữ liệu
            }
            jobs.reopenForRetry(jobId, retried);
            log.info("Retrying {} API-failed rows jobId={}", retried, jobId);
            return new RetryResult(jobId, retried, JobStatus.PROCESSING);
        });
    }
}
