package com.app.importservice.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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

    public void deleteByJob(long jobId) {
        while (jdbc.update("DELETE FROM import_row WHERE job_id = ? LIMIT " + DELETE_BATCH, jobId) > 0) {
            // lặp tới khi hết
        }
    }
}
