package com.app.importservice.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class ImportRowRepository {

    private static final String INSERT_SQL = """
            INSERT INTO import_row (job_id, row_no,
                external_id, full_name, email, phone, date_of_birth,
                gender, address, city, country, postal_code,
                company, job_title, annual_income, signup_date, customer_status,
                status, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Xoá theo từng đợt nhỏ: 1 câu DELETE 1 triệu dòng giữ lock và undo log rất lâu. */
    private static final int DELETE_BATCH = 10_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImportRowRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Ghi 1 chunk trong 1 transaction. Với rewriteBatchedStatements=true, driver MySQL gộp cả chunk
     * thành vài câu INSERT ... VALUES (...), (...), ... thay vì gửi từng câu một.
     * Dòng hợp lệ = NEW (chờ worker), dòng sai = FAILED kèm lý do.
     */
    public void insertChunk(long jobId, List<StagedRow> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (StagedRow row : rows) {
            Object[] a = new Object[4 + CustomerCsv.COLUMNS];
            a[0] = jobId;
            a[1] = row.rowNo();
            System.arraycopy(row.values(), 0, a, 2, CustomerCsv.COLUMNS);
            a[2 + CustomerCsv.COLUMNS] = row.valid() ? "NEW" : "FAILED";
            a[3 + CustomerCsv.COLUMNS] = row.error();
            args.add(a);
        }
        tx.executeWithoutResult(status -> jdbc.batchUpdate(INSERT_SQL, args));
    }

    /** 1 dòng đã được worker "nhận" (status PROCESSING), kèm dữ liệu thô để gửi API và ghi bảng đích. */
    public record ClaimedRow(int rowNo, String[] values) {
    }

    /**
     * Worker "nhận" tối đa {@code limit} dòng NEW của 1 job trong 1 transaction NGẮN rồi commit ngay:
     * <pre>
     * SELECT ... WHERE status = 'NEW' AND job_id = ? ORDER BY row_no LIMIT ? FOR UPDATE SKIP LOCKED
     * UPDATE ... SET status = 'PROCESSING', claimed_at = NOW(3), attempts = attempts + 1
     * </pre>
     * {@code FOR UPDATE} khoá các dòng vừa chọn; {@code SKIP LOCKED} làm worker khác BỎ QUA dòng đang bị khoá
     * (thay vì đứng chờ) → 8 worker / nhiều pod cùng chạy câu này vẫn lấy các dòng khác nhau.
     * Sau commit dòng đã là PROCESSING nên không ai lấy lại; lúc gọi API không còn giữ lock / connection nào.
     */
    public List<ClaimedRow> claim(long jobId, int limit) {
        return tx.execute(status -> {
            List<ClaimedRow> claimed = jdbc.query("""
                            SELECT row_no, external_id, full_name, email, phone, date_of_birth,
                                   gender, address, city, country, postal_code,
                                   company, job_title, annual_income, signup_date, customer_status
                            FROM import_row
                            WHERE status = 'NEW' AND job_id = ?
                            ORDER BY row_no
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (rs, i) -> {
                        String[] values = new String[CustomerCsv.COLUMNS];
                        for (int c = 0; c < CustomerCsv.COLUMNS; c++) {
                            values[c] = rs.getString(c + 2);
                        }
                        return new ClaimedRow(rs.getInt(1), values);
                    },
                    jobId, limit);
            if (!claimed.isEmpty()) {
                List<Object> args = new ArrayList<>(claimed.size() + 1);
                args.add(jobId);
                claimed.forEach(row -> args.add(row.rowNo()));
                jdbc.update("UPDATE import_row SET status = 'PROCESSING', claimed_at = NOW(3), attempts = attempts + 1 "
                        + "WHERE job_id = ? AND row_no IN (" + placeholders(claimed.size()) + ")", args.toArray());
            }
            return claimed;
        });
    }

    /** Gọi trong transaction ghi kết quả chunk. */
    public void markDone(long jobId, List<Integer> rowNos) {
        if (rowNos.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>(rowNos.size() + 1);
        args.add(jobId);
        args.addAll(rowNos);
        jdbc.update("UPDATE import_row SET status = 'DONE', error = NULL WHERE job_id = ? AND row_no IN ("
                + placeholders(rowNos.size()) + ")", args.toArray());
    }

    /** Gọi trong transaction ghi kết quả chunk. */
    public void markFailed(long jobId, List<Integer> rowNos, String error) {
        if (rowNos.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>(rowNos.size() + 2);
        args.add(error.length() <= 1000 ? error : error.substring(0, 1000));
        args.add(jobId);
        args.addAll(rowNos);
        jdbc.update("UPDATE import_row SET status = 'FAILED', error = ? WHERE job_id = ? AND row_no IN ("
                + placeholders(rowNos.size()) + ")", args.toArray());
    }

    /**
     * Dòng PROCESSING quá lâu = worker/pod đã chết khi đang xử lý → trả về NEW cho worker khác làm tiếp.
     * Dòng đó có thể đã được ghi vào customer trước khi chết; làm lại vẫn an toàn nhờ UNIQUE(job_id, row_no).
     *
     * @return số dòng được trả về
     */
    public int releaseStaleClaims(Duration staleAfter) {
        int total = 0;
        int updated;
        do {
            updated = jdbc.update("UPDATE import_row SET status = 'NEW', claimed_at = NULL "
                    + "WHERE status = 'PROCESSING' AND claimed_at < NOW(3) - INTERVAL ? SECOND LIMIT " + DELETE_BATCH,
                    staleAfter.toSeconds());
            total += updated;
        } while (updated > 0);
        return total;
    }

    /** Job còn dòng nào chờ / đang xử lý không. Đọc thường (không khoá), dùng index (status, job_id, row_no). */
    public boolean hasPendingRows(long jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM import_row WHERE status = 'NEW' AND job_id = ?)
                    OR EXISTS (SELECT 1 FROM import_row WHERE status = 'PROCESSING' AND job_id = ?)
                """, Boolean.class, jobId, jobId));
    }

    public boolean hasFailedRows(long jobId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM import_row WHERE status = 'FAILED' AND job_id = ?)",
                Boolean.class, jobId));
    }

    /**
     * Dòng FAILED có 2 loại, phân biệt bằng {@code attempts} (số lần worker đã nhận dòng):
     * INVALID = sai dữ liệu lúc ingest (attempts = 0, chưa từng gửi API); API = gửi API thất bại (attempts >= 1).
     */
    public enum FailureReason {
        INVALID, API
    }

    public record FailedRow(int rowNo, String externalId, FailureReason reason, String error) {
    }

    /**
     * Phân trang kiểu "keyset": lấy dòng có row_no > afterRowNo thay vì OFFSET. OFFSET 900000 bắt MySQL đọc rồi bỏ
     * 900.000 dòng; keyset nhảy thẳng tới vị trí nhờ index (status, job_id, row_no) → trang nào cũng nhanh như nhau.
     */
    public List<FailedRow> findFailed(long jobId, int afterRowNo, int limit) {
        return jdbc.query("""
                        SELECT row_no, external_id, attempts, error FROM import_row
                        WHERE status = 'FAILED' AND job_id = ? AND row_no > ?
                        ORDER BY row_no
                        LIMIT ?
                        """,
                (rs, i) -> new FailedRow(rs.getInt("row_no"), rs.getString("external_id"),
                        rs.getInt("attempts") == 0 ? FailureReason.INVALID : FailureReason.API, rs.getString("error")),
                jobId, afterRowNo, limit);
    }

    /** Dòng lỗi API → NEW để worker xử lý lại. Dòng sai dữ liệu (attempts = 0) giữ nguyên. */
    public int resetApiFailures(long jobId) {
        return jdbc.update("UPDATE import_row SET status = 'NEW', error = NULL, claimed_at = NULL "
                + "WHERE status = 'FAILED' AND job_id = ? AND attempts > 0", jobId);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public void deleteByJob(long jobId) {
        while (jdbc.update("DELETE FROM import_row WHERE job_id = ? LIMIT " + DELETE_BATCH, jobId) > 0) {
            // lặp tới khi hết
        }
    }
}
