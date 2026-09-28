package com.app.importservice.process;

import com.app.importservice.client.ThirdPartyClient.VerifyResult;
import com.app.importservice.ingest.ImportRowRepository.ClaimedRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
public class CustomerRepository {

    /**
     * ON DUPLICATE KEY UPDATE id = id: dòng (job_id, row_no) đã có thì bỏ qua → chạy lại / worker chết giữa chừng
     * cũng không sinh bản ghi trùng. Không dùng INSERT IGNORE vì nó nuốt luôn cả lỗi khác (sai kiểu, quá dài...).
     */
    private static final String INSERT_SQL = """
            INSERT INTO customer (job_id, row_no,
                external_id, full_name, email, phone, date_of_birth,
                gender, address, city, country, postal_code,
                company, job_title, annual_income, signup_date, customer_status,
                verified, risk_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """;

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VerifiedRow(ClaimedRow row, VerifyResult result) {
    }

    /** Gọi trong transaction ghi kết quả chunk. Dữ liệu đã được kiểm tra lúc ingest nên chuyển kiểu không lỗi. */
    public void insert(long jobId, List<VerifiedRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (VerifiedRow verified : rows) {
            String[] v = verified.row().values();
            args.add(new Object[]{
                    jobId, verified.row().rowNo(),
                    v[0], v[1], v[2], v[3], toDate(v[4]),
                    v[5], v[6], v[7], v[8], v[9],
                    v[10], v[11], v[12] == null ? null : new BigDecimal(v[12]), toDate(v[13]), v[14],
                    verified.result().verified(), verified.result().riskScore()});
        }
        jdbc.batchUpdate(INSERT_SQL, args);
    }

    private static LocalDate toDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
