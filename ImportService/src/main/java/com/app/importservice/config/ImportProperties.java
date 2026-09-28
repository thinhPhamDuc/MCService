package com.app.importservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Cấu hình import (prefix {@code import.*} trong application.properties).
 * Số thread / kích thước chunk KHÔNG phụ thuộc số dòng của file — xem docs/csv-import-design.md mục 1, 3.
 * Mỗi giá trị có mặc định trong code: thiếu cấu hình thì app vẫn chạy (không ra writers = 0 / ingest = null).
 * Vì sao chọn các con số này: docs/csv-import-implementation.md mục "Thông số".
 */
@ConfigurationProperties("import")
public record ImportProperties(Path storageDir,
                               @DefaultValue Ingest ingest,
                               @DefaultValue Process process,
                               @DefaultValue Api api) {

    /**
     * Giai đoạn 1 — đọc file vào bảng staging.
     *
     * @param writers           số thread ghi staging song song
     * @param chunkSize         số dòng / 1 lần batch insert
     * @param queueCapacity     số chunk tối đa chờ ghi; đầy thì reader dừng đọc (backpressure)
     * @param maxConcurrentJobs số file được ingest cùng lúc; file khác chờ ở trạng thái QUEUED
     * @param heartbeatInterval reader ghi nhịp tim vào import_job mỗi khoảng này
     * @param staleAfter        job INGESTING không có nhịp tim quá thời gian này coi như pod đã chết giữa chừng
     * @param queuedTimeout     job QUEUED lâu hơn thế này coi như hàng đợi (trong RAM) đã mất cùng pod
     */
    public record Ingest(@DefaultValue("6") int writers,
                         @DefaultValue("1000") int chunkSize,
                         @DefaultValue("10") int queueCapacity,
                         @DefaultValue("1") int maxConcurrentJobs,
                         @DefaultValue("10s") Duration heartbeatInterval,
                         @DefaultValue("2m") Duration staleAfter,
                         @DefaultValue("30m") Duration queuedTimeout) {
    }

    /**
     * Giai đoạn 2 — worker lấy dòng NEW, gọi API, ghi bảng customer.
     *
     * @param enabled      tắt thì không chạy worker (test tự gọi {@code processNextChunk})
     * @param workers      số worker (virtual thread) = số chunk được xử lý cùng lúc
     * @param claimSize    số dòng 1 worker lấy mỗi lần (1 transaction claim + 1 transaction ghi kết quả)
     * @param pollInterval không còn việc thì worker ngủ bấy lâu rồi hỏi lại
     * @param staleAfter   dòng PROCESSING quá thời gian này (worker/pod chết) được trả về NEW cho worker khác
     */
    public record Process(@DefaultValue("true") boolean enabled,
                          @DefaultValue("8") int workers,
                          @DefaultValue("500") int claimSize,
                          @DefaultValue("1s") Duration pollInterval,
                          @DefaultValue("5m") Duration staleAfter) {
    }

    /**
     * API bên thứ ba (ThirdPartyMock).
     *
     * @param baseUrl           địa chỉ API
     * @param batchSize         số khách hàng / 1 request bulk (mock cho tối đa 100)
     * @param maxConcurrent     số request đang gọi cùng lúc tối đa, chung cho mọi worker (Semaphore)
     * @param maxAttempts       số lần thử khi lỗi 5xx / timeout / mất kết nối
     * @param initialBackoff    chờ trước lần thử lại đầu; mỗi lần sau gấp đôi
     * @param maxRateLimitWaits số lần tối đa chờ vì 429 cho 1 request (không tính vào maxAttempts)
     * @param connectTimeout    timeout kết nối
     * @param readTimeout       timeout chờ phản hồi
     */
    public record Api(@DefaultValue("http://localhost:8090") URI baseUrl,
                      @DefaultValue("100") int batchSize,
                      @DefaultValue("16") int maxConcurrent,
                      @DefaultValue("3") int maxAttempts,
                      @DefaultValue("200ms") Duration initialBackoff,
                      @DefaultValue("20") int maxRateLimitWaits,
                      @DefaultValue("2s") Duration connectTimeout,
                      @DefaultValue("5s") Duration readTimeout) {
    }
}
