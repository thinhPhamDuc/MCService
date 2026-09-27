-- Schema cho import CSV — xem docs/csv-import-design.md mục 4

-- 1 dòng / 1 file upload, giữ trạng thái và bộ đếm tiến độ
CREATE TABLE import_job (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    file_name      VARCHAR(255)  NOT NULL,
    -- QUEUED -> INGESTING -> PROCESSING -> COMPLETED / COMPLETED_WITH_ERRORS / FAILED
    status         VARCHAR(32)   NOT NULL,
    total_rows     INT           NOT NULL DEFAULT 0,
    processed_rows INT           NOT NULL DEFAULT 0,
    failed_rows    INT           NOT NULL DEFAULT 0,
    invalid_rows   INT           NOT NULL DEFAULT 0,
    error          VARCHAR(1000) NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at     DATETIME(3)   NULL,
    finished_at    DATETIME(3)   NULL,
    PRIMARY KEY (id),
    INDEX idx_import_job_status (status)
) ENGINE = InnoDB;

-- Staging: dữ liệu THÔ (chuỗi) đúng như trong file, để dòng sai định dạng vẫn lưu được kèm lý do lỗi.
-- PK (job_id, row_no) = số dòng trong file -> chạy lại không sinh bản ghi trùng.
CREATE TABLE import_row (
    job_id          BIGINT        NOT NULL,
    row_no          INT           NOT NULL,
    external_id     VARCHAR(64)   NULL,
    full_name       VARCHAR(255)  NULL,
    email           VARCHAR(255)  NULL,
    phone           VARCHAR(32)   NULL,
    date_of_birth   VARCHAR(32)   NULL,
    gender          VARCHAR(16)   NULL,
    address         VARCHAR(500)  NULL,
    city            VARCHAR(100)  NULL,
    country         VARCHAR(100)  NULL,
    postal_code     VARCHAR(20)   NULL,
    company         VARCHAR(255)  NULL,
    job_title       VARCHAR(255)  NULL,
    annual_income   VARCHAR(32)   NULL,
    signup_date     VARCHAR(32)   NULL,
    -- cột "status" trong CSV (trạng thái khách hàng), đổi tên để không trùng trạng thái xử lý bên dưới
    customer_status VARCHAR(32)   NULL,
    -- NEW -> PROCESSING -> DONE / FAILED
    status          VARCHAR(16)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    error           VARCHAR(1000) NULL,
    claimed_at      DATETIME(3)   NULL,
    PRIMARY KEY (job_id, row_no),
    -- phục vụ câu claim của worker: WHERE status = 'NEW' ... LIMIT 500 FOR UPDATE SKIP LOCKED
    INDEX idx_import_row_claim (status, job_id, row_no),
    CONSTRAINT fk_import_row_job FOREIGN KEY (job_id) REFERENCES import_job (id)
) ENGINE = InnoDB;

-- Bảng đích: dữ liệu đã chuẩn hoá kiểu + kết quả từ API bên thứ ba
CREATE TABLE customer (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    job_id          BIGINT        NOT NULL,
    row_no          INT           NOT NULL,
    external_id     VARCHAR(64)   NOT NULL,
    full_name       VARCHAR(255)  NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    phone           VARCHAR(32)   NULL,
    date_of_birth   DATE          NULL,
    gender          VARCHAR(16)   NULL,
    address         VARCHAR(500)  NULL,
    city            VARCHAR(100)  NULL,
    country         VARCHAR(100)  NULL,
    postal_code     VARCHAR(20)   NULL,
    company         VARCHAR(255)  NULL,
    job_title       VARCHAR(255)  NULL,
    annual_income   DECIMAL(15,2) NULL,
    signup_date     DATE          NULL,
    customer_status VARCHAR(32)   NULL,
    verified        BOOLEAN       NOT NULL,
    risk_score      INT           NOT NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- idempotent: INSERT ... ON DUPLICATE KEY UPDATE id = id khi worker xử lý lại cùng 1 dòng
    UNIQUE KEY uk_customer_job_row (job_id, row_no),
    INDEX idx_customer_external_id (external_id)
) ENGINE = InnoDB;
