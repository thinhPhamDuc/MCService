-- Ingest ghi "nhịp tim" định kỳ. Sweeper xét "lâu rồi không có nhịp tim" thay vì "bắt đầu đã lâu":
-- file rất lớn chạy lâu nhưng vẫn đang tiến triển thì không bị đánh FAILED nhầm.
ALTER TABLE import_job ADD COLUMN heartbeat_at DATETIME(3) NULL AFTER started_at;
