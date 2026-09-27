package com.app.importservice.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Định dạng file CSV khách hàng (15 cột) và luật kiểm tra từng dòng — docs/csv-import-design.md mục 5.
 */
public final class CustomerCsv {

    public static final List<String> HEADER = List.of(
            "external_id", "full_name", "email", "phone", "date_of_birth",
            "gender", "address", "city", "country", "postal_code",
            "company", "job_title", "annual_income", "signup_date", "status");

    public static final int COLUMNS = HEADER.size();

    /** Độ dài tối đa từng cột = kích thước VARCHAR trong import_row (V1__init_schema.sql). */
    private static final int[] MAX_LENGTH = {64, 255, 255, 32, 32, 16, 500, 100, 100, 20, 255, 255, 32, 32, 32};

    private static final int EXTERNAL_ID = 0;
    private static final int FULL_NAME = 1;
    private static final int EMAIL = 2;
    private static final int DATE_OF_BIRTH = 4;
    private static final int ANNUAL_INCOME = 12;
    private static final int SIGNUP_DATE = 13;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_ERROR_LENGTH = 1000;

    private CustomerCsv() {
    }

    /** Header sai (thiếu/thừa/sai thứ tự cột) thì từ chối cả file: không đoán được cột nào là cột nào. */
    public static void checkHeader(List<String> actual) {
        List<String> normalized = actual.stream().map(h -> h.strip().toLowerCase()).toList();
        if (!normalized.equals(HEADER)) {
            throw new IllegalArgumentException("Invalid CSV header. Expected " + HEADER + " but got " + actual);
        }
    }

    /**
     * Chuẩn hoá + kiểm tra 1 dòng. Dòng sai KHÔNG làm hỏng cả file: vẫn trả về StagedRow (kèm lỗi)
     * để lưu lại cho người dùng xem. Giá trị quá dài bị cắt cho vừa cột, nếu không MySQL sẽ từ chối cả batch.
     */
    public static StagedRow toStagedRow(int rowNo, List<String> raw) {
        List<String> errors = new ArrayList<>();
        if (raw.size() != COLUMNS) {
            errors.add("expected " + COLUMNS + " columns but got " + raw.size());
        }

        String[] values = new String[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) {
            String value = i < raw.size() ? blankToNull(raw.get(i)) : null;
            if (value != null && value.length() > MAX_LENGTH[i]) {
                errors.add(HEADER.get(i) + " longer than " + MAX_LENGTH[i]);
                value = value.substring(0, MAX_LENGTH[i]);
            }
            values[i] = value;
        }

        require(values, EXTERNAL_ID, errors);
        require(values, FULL_NAME, errors);
        require(values, EMAIL, errors);
        if (values[EMAIL] != null && !EMAIL_PATTERN.matcher(values[EMAIL]).matches()) {
            errors.add("email is invalid");
        }
        checkDate(values, DATE_OF_BIRTH, errors);
        checkDate(values, SIGNUP_DATE, errors);
        checkIncome(values[ANNUAL_INCOME], errors);

        return new StagedRow(rowNo, values, errors.isEmpty() ? null : truncate(String.join("; ", errors)));
    }

    private static void require(String[] values, int column, List<String> errors) {
        if (values[column] == null) {
            errors.add(HEADER.get(column) + " is required");
        }
    }

    /** Ngày theo ISO: yyyy-MM-dd. */
    private static void checkDate(String[] values, int column, List<String> errors) {
        if (values[column] == null) {
            return;
        }
        try {
            LocalDate.parse(values[column]);
        } catch (DateTimeParseException e) {
            errors.add(HEADER.get(column) + " must be yyyy-MM-dd");
        }
    }

    /** Khớp cột DECIMAL(15,2) của bảng customer: tối đa 13 chữ số phần nguyên, 2 chữ số thập phân, không âm. */
    private static void checkIncome(String value, List<String> errors) {
        if (value == null) {
            return;
        }
        try {
            BigDecimal income = new BigDecimal(value);
            if (income.signum() < 0 || income.scale() > 2 || income.precision() - income.scale() > 13) {
                errors.add("annual_income out of range");
            }
        } catch (NumberFormatException e) {
            errors.add("annual_income is not a number");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
