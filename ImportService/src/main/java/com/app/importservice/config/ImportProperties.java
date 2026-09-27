package com.app.importservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Cấu hình import (prefix {@code import.*} trong application.properties).
 * Số thread / kích thước chunk KHÔNG phụ thuộc số dòng của file — xem docs/csv-import-design.md mục 1, 3.
 */
@ConfigurationProperties("import")
public record ImportProperties(Path storageDir, Ingest ingest) {

    /**
     * @param writers           số thread ghi staging song song
     * @param chunkSize         số dòng / 1 lần batch insert
     * @param queueCapacity     số chunk tối đa chờ ghi; đầy thì reader dừng đọc (backpressure)
     * @param maxConcurrentJobs số file được ingest cùng lúc; file khác chờ ở trạng thái QUEUED
     * @param staleAfter        job INGESTING quá thời gian này coi như pod đã chết giữa chừng
     */
    public record Ingest(int writers, int chunkSize, int queueCapacity, int maxConcurrentJobs, Duration staleAfter) {
    }
}
