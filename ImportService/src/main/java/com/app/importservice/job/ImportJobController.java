package com.app.importservice.job;

import com.app.importservice.config.ImportProperties;
import com.app.importservice.ingest.ImportRowRepository;
import com.app.importservice.ingest.ImportRowRepository.FailedRow;
import com.app.importservice.ingest.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/imports")
public class ImportJobController {

    private static final Logger log = LoggerFactory.getLogger(ImportJobController.class);

    private static final int MAX_ERROR_PAGE = 1000;

    private final ImportJobRepository jobs;
    private final ImportRowRepository rows;
    private final IngestService ingestService;
    private final RetryFailedService retryFailedService;
    private final Path storageDir;

    public ImportJobController(ImportJobRepository jobs, ImportRowRepository rows, IngestService ingestService,
                               RetryFailedService retryFailedService, ImportProperties properties) {
        this.jobs = jobs;
        this.rows = rows;
        this.ingestService = ingestService;
        this.retryFailedService = retryFailedService;
        this.storageDir = properties.storageDir();
    }

    public record UploadResponse(long jobId, JobStatus status, String statusUrl) {
    }

    /**
     * Nhận file, lưu ra đĩa, trả 202 ngay. Đọc 1 triệu dòng mất vài chục giây — không bắt client giữ kết nối chờ.
     * Ví dụ: curl -F file=@customers.csv localhost:8084/imports
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        String fileName = StringUtils.hasText(file.getOriginalFilename())
                ? StringUtils.getFilename(StringUtils.cleanPath(file.getOriginalFilename()))
                : "upload.csv";

        long jobId = jobs.create(fileName);
        Path target = storageDir.resolve(jobId + ".csv");
        try {
            Files.createDirectories(storageDir);
            file.transferTo(target);
        } catch (IOException e) {
            jobs.markFailed(jobId, "Could not store uploaded file: " + e.getMessage());
            throw e;
        }
        ingestService.submit(jobId, target);
        log.info("Accepted import jobId={} file={} size={} bytes", jobId, fileName, file.getSize());

        String statusUrl = "/imports/" + jobId;
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(new UploadResponse(jobId, JobStatus.QUEUED, statusUrl));
    }

    /**
     * @param progressPercent (đã gửi API + dòng sai dữ liệu) / tổng số dòng — dòng sai dữ liệu cũng coi là "đã xong"
     * @param rowsPerSecond   (đã gửi API + dòng sai dữ liệu) / số giây đã chạy (gồm cả thời gian ingest)
     */
    public record JobView(long jobId, String fileName, JobStatus status,
                          int totalRows, int processedRows, int failedRows, int invalidRows,
                          double progressPercent, Long durationSeconds, Long rowsPerSecond, String error,
                          LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) {

        static JobView from(ImportJob job) {
            int done = job.processedRows() + job.invalidRows();
            double percent = job.totalRows() == 0
                    ? (job.finishedAt() != null ? 100.0 : 0.0)
                    : Math.round(done * 1000.0 / job.totalRows()) / 10.0;
            Long rowsPerSecond = job.durationSeconds() == null || job.durationSeconds() == 0
                    ? null : done / job.durationSeconds();
            return new JobView(job.id(), job.fileName(), job.status(),
                    job.totalRows(), job.processedRows(), job.failedRows(), job.invalidRows(),
                    percent, job.durationSeconds(), rowsPerSecond, job.error(),
                    job.createdAt(), job.startedAt(), job.finishedAt());
        }
    }

    @GetMapping("/{jobId}")
    public JobView get(@PathVariable long jobId) {
        return JobView.from(findJob(jobId));
    }

    /**
     * @param nextAfterRowNo truyền vào {@code afterRowNo} để lấy trang sau; null = hết
     */
    public record ErrorPage(long jobId, List<FailedRow> items, Integer nextAfterRowNo) {
    }

    /**
     * Danh sách dòng lỗi, trang nào cũng nhanh (keyset pagination). Ví dụ:
     * GET /imports/1/errors?limit=100  →  GET /imports/1/errors?limit=100&afterRowNo={nextAfterRowNo}
     */
    @GetMapping("/{jobId}/errors")
    public ErrorPage errors(@PathVariable long jobId,
                            @RequestParam(defaultValue = "0") int afterRowNo,
                            @RequestParam(defaultValue = "100") int limit) {
        findJob(jobId);
        if (limit < 1 || limit > MAX_ERROR_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_ERROR_PAGE);
        }
        List<FailedRow> items = rows.findFailed(jobId, afterRowNo, limit);
        Integer next = items.size() == limit ? items.getLast().rowNo() : null;
        return new ErrorPage(jobId, items, next);
    }

    /** Chạy lại dòng lỗi API (sau khi bên thứ ba hết sự cố). 409 nếu job chưa xong / không có lỗi. */
    @PostMapping("/{jobId}/retry-failed")
    public RetryFailedService.RetryResult retryFailed(@PathVariable long jobId) {
        return retryFailedService.retryFailed(jobId);
    }

    private ImportJob findJob(long jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found"));
    }
}
