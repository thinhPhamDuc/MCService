package com.app.importservice.job;

import java.time.LocalDateTime;

/**
 * @param durationSeconds từ lúc bắt đầu ingest tới lúc xong (đang chạy thì tới hiện tại); null nếu chưa bắt đầu.
 *                        Tính trong MySQL để không lệch múi giờ giữa app và DB.
 */
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
        LocalDateTime heartbeatAt,
        LocalDateTime finishedAt,
        Long durationSeconds) {
}
