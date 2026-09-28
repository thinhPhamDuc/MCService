package com.app.importservice.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ImportJobRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final RowMapper<ImportJob> MAPPER = (rs, i) -> new ImportJob(
            rs.getLong("id"),
            rs.getString("file_name"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("total_rows"),
            rs.getInt("processed_rows"),
            rs.getInt("failed_rows"),
            rs.getInt("invalid_rows"),
            rs.getString("error"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("heartbeat_at", LocalDateTime.class),
            rs.getObject("finished_at", LocalDateTime.class),
            rs.getObject("duration_seconds", Long.class));

    private final JdbcTemplate jdbc;

    public ImportJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String fileName) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO import_job (file_name, status) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileName);
            ps.setString(2, JobStatus.QUEUED.name());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ImportJob> findById(long id) {
        return jdbc.query("""
                SELECT *, TIMESTAMPDIFF(SECOND, started_at, COALESCE(finished_at, NOW(3))) AS duration_seconds
                FROM import_job WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public void markIngesting(long id) {
        jdbc.update("UPDATE import_job SET status = ?, started_at = NOW(3), heartbeat_at = NOW(3) WHERE id = ?",
                JobStatus.INGESTING.name(), id);
    }

    public void markIngested(long id, int totalRows, int invalidRows) {
        jdbc.update("UPDATE import_job SET status = ?, total_rows = ?, invalid_rows = ? WHERE id = ?",
                JobStatus.PROCESSING.name(), totalRows, invalidRows, id);
    }

    public void markFinished(long id, JobStatus status) {
        jdbc.update("UPDATE import_job SET status = ?, finished_at = NOW(3) WHERE id = ?", status.name(), id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("UPDATE import_job SET status = ?, error = ?, finished_at = NOW(3) WHERE id = ?",
                JobStatus.FAILED.name(), truncate(error), id);
    }

    /**
     * Job cũ nhất đã ingest xong (PROCESSING) mà còn dòng NEW. Worker ưu tiên job cũ trước (FIFO):
     * người upload trước xong trước. Chỉ lấy job PROCESSING, không lấy job đang INGESTING:
     * nếu ingest lỗi giữa chừng, dữ liệu dở bị xoá — worker không được xử lý nửa file đó.
     */
    public Optional<Long> findJobWithNewRows() {
        return jdbc.queryForList("""
                SELECT j.id FROM import_job j
                WHERE j.status = 'PROCESSING'
                  AND EXISTS (SELECT 1 FROM import_row r WHERE r.status = 'NEW' AND r.job_id = j.id)
                ORDER BY j.id
                LIMIT 1
                """, Long.class).stream().findFirst();
    }

    public List<Long> findProcessing() {
        return jdbc.queryForList("SELECT id FROM import_job WHERE status = ?", Long.class, JobStatus.PROCESSING.name());
    }

    /** Cộng tiến độ sau mỗi chunk (gọi trong transaction ghi kết quả chunk). */
    public void addProgress(long id, int processed, int failed) {
        jdbc.update("UPDATE import_job SET processed_rows = processed_rows + ?, failed_rows = failed_rows + ? WHERE id = ?",
                processed, failed, id);
    }

    /**
     * Đưa job đã xong-có-lỗi về PROCESSING để worker xử lý lại {@code retried} dòng, trừ các dòng đó khỏi bộ đếm
     * (worker sẽ cộng lại khi xử lý xong). Gọi trong transaction đã khoá job ({@link #lockStatus}).
     */
    public void reopenForRetry(long id, int retried) {
        jdbc.update("""
                UPDATE import_job
                SET status = ?, finished_at = NULL, error = NULL,
                    processed_rows = processed_rows - ?, failed_rows = failed_rows - ?
                WHERE id = ?
                """, JobStatus.PROCESSING.name(), retried, retried, id);
    }

    /** SELECT ... FOR UPDATE: khoá dòng job tới hết transaction, 2 lệnh retry cùng lúc sẽ chạy lần lượt. */
    public Optional<JobStatus> lockStatus(long id) {
        return jdbc.queryForList("SELECT status FROM import_job WHERE id = ? FOR UPDATE", String.class, id)
                .stream().findFirst().map(JobStatus::valueOf);
    }

    /** Chỉ kết thúc job đang PROCESSING: 2 worker cùng gọi thì chỉ 1 người đổi được (trả về true). */
    public boolean finishIfProcessing(long id, JobStatus status) {
        return jdbc.update("UPDATE import_job SET status = ?, finished_at = NOW(3) WHERE id = ? AND status = ?",
                status.name(), id, JobStatus.PROCESSING.name()) == 1;
    }

    /** Ingest còn sống: reader gọi định kỳ (import.ingest.heartbeat-interval). */
    public void heartbeat(long id) {
        jdbc.update("UPDATE import_job SET heartbeat_at = NOW(3) WHERE id = ? AND status = ?",
                id, JobStatus.INGESTING.name());
    }

    /** Job INGESTING không có nhịp tim trong (now - staleAfter): pod xử lý nó nhiều khả năng đã chết. */
    public List<Long> findStaleIngesting(Duration staleAfter) {
        return jdbc.queryForList(
                "SELECT id FROM import_job WHERE status = ? AND COALESCE(heartbeat_at, started_at) < NOW(3) - INTERVAL ? SECOND",
                Long.class, JobStatus.INGESTING.name(), staleAfter.toSeconds());
    }

    /** Chỉ chuyển FAILED nếu job vẫn đang INGESTING (tránh ghi đè khi job vừa kịp xong). */
    public boolean failIfStillIngesting(long id, String error) {
        return jdbc.update("UPDATE import_job SET status = ?, error = ?, finished_at = NOW(3) WHERE id = ? AND status = ?",
                JobStatus.FAILED.name(), truncate(error), id, JobStatus.INGESTING.name()) == 1;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
