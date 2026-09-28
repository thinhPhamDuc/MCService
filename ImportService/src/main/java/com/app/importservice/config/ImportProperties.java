package com.app.importservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Cấu hình import (prefix {@code import.*} trong application.properties).
 * Số thread / kích thước chunk KHÔNG phụ thuộc số dòng của file — xem docs/csv-import-design.md mục 1, 3.
 * Mỗi giá trị có mặc định trong code: thiếu cấu hình thì app vẫn chạy (không ra writers = 0 / ingest = null).
 * Vì sao chọn các con số này: docs/csv-import-implementation.md mục "Thông số".
 */
@ConfigurationProperties("import")
public record ImportProperties(Path storageDir, @DefaultValue Ingest ingest) {

    /**
     * @param writers           số thread ghi staging song song
     * @param chunkSize         số dòng / 1 lần batch insert
     * @param queueCapacity     số chunk tối đa chờ ghi; đầy thì reader dừng đọc (backpressure)
     * @param maxConcurrentJobs số file được ingest cùng lúc; file khác chờ ở trạng thái QUEUED
     * @param staleAfter        job INGESTING quá thời gian này coi như pod đã chết giữa chừng
     */
    public record Ingest(@DefaultValue("6") int writers,
                         @DefaultValue("1000") int chunkSize,
                         @DefaultValue("10") int queueCapacity,
                         @DefaultValue("1") int maxConcurrentJobs,
                         @DefaultValue("10m") Duration staleAfter) {
    }
}
