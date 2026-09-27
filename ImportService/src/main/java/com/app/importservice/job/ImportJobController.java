package com.app.importservice.job;

import com.app.importservice.config.ImportProperties;
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

@RestController
@RequestMapping("/imports")
public class ImportJobController {

    private static final Logger log = LoggerFactory.getLogger(ImportJobController.class);

    private final ImportJobRepository jobs;
    private final IngestService ingestService;
    private final Path storageDir;

    public ImportJobController(ImportJobRepository jobs, IngestService ingestService, ImportProperties properties) {
        this.jobs = jobs;
        this.ingestService = ingestService;
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

    @GetMapping("/{jobId}")
    public ImportJob get(@PathVariable long jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found"));
    }
}
