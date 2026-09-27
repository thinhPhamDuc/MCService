package com.app.importservice.job;

public enum JobStatus {
    /** Đã nhận file, chờ tới lượt ingest. */
    QUEUED,
    /** Đang đọc file và ghi vào bảng staging import_row. */
    INGESTING,
    /** Ingest xong, worker đang gọi API bên thứ ba và ghi bảng đích (Phase 3). */
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
