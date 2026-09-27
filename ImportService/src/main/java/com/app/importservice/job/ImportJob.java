package com.app.importservice.job;

import java.time.LocalDateTime;

public record ImportJob(
        long id,
        String fileName,
        JobStatus status,
        int totalRows,
        int processedRows,
        int failedRows,
        int invalidRows,
        String error,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
