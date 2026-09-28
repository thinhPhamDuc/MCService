package com.app.importservice.ingest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerCsvTest {

    private static List<String> validRow() {
        return new ArrayList<>(List.of("C-1", "Alice", "alice@example.com", "0900000001", "1990-01-15", "F",
                "12 Nguyen Trai, Q1", "Ho Chi Minh", "VN", "700000", "ACME", "Engineer", "120000.50",
                "2024-03-01", "ACTIVE"));
    }

    @Test
    void validRowHasNoError() {
        StagedRow row = CustomerCsv.toStagedRow(1, validRow());

        assertThat(row.valid()).isTrue();
        assertThat(row.values()).hasSize(15);
    }

    @Test
    void valuesAreTrimmedAndBlankBecomesNull() {
        List<String> raw = validRow();
        raw.set(1, "  Alice  ");
        raw.set(3, "   ");

        StagedRow row = CustomerCsv.toStagedRow(1, raw);

        assertThat(row.values()[1]).isEqualTo("Alice");
        assertThat(row.values()[3]).isNull();
        assertThat(row.valid()).isTrue();
    }

    @Test
    void missingRequiredFieldsAreAllReported() {
        List<String> raw = validRow();
        raw.set(0, "");
        raw.set(2, "");

        StagedRow row = CustomerCsv.toStagedRow(1, raw);

        assertThat(row.error()).contains("external_id is required").contains("email is required");
    }

    @Test
    void invalidEmailDateAndIncomeAreRejected() {
        List<String> raw = validRow();
        raw.set(2, "not-an-email");
        raw.set(4, "15/01/1990");
        raw.set(12, "12.345");

        StagedRow row = CustomerCsv.toStagedRow(1, raw);

        assertThat(row.error())
                .contains("email is invalid")
                .contains("date_of_birth must be yyyy-MM-dd")
                .contains("annual_income out of range");
    }

    @Test
    void wrongColumnCountIsRejectedButKeepsValues() {
        StagedRow row = CustomerCsv.toStagedRow(7, validRow().subList(0, 3));

        assertThat(row.error()).contains("expected 15 columns but got 3");
        assertThat(row.values()).hasSize(15);
        assertThat(row.values()[0]).isEqualTo("C-1");
        assertThat(row.values()[14]).isNull();
    }

    @Test
    void tooLongValueIsTruncatedToColumnSize() {
        List<String> raw = validRow();
        raw.set(0, "x".repeat(100));

        StagedRow row = CustomerCsv.toStagedRow(1, raw);

        assertThat(row.error()).contains("external_id longer than 64");
        assertThat(row.values()[0]).hasSize(64);
    }

    @Test
    void headerIsCaseAndSpaceInsensitive() {
        List<String> header = new ArrayList<>(CustomerCsv.HEADER);
        header.set(0, " External_ID ");

        CustomerCsv.checkHeader(header);
    }

    @Test
    void wrongHeaderIsRejected() {
        List<String> header = new ArrayList<>(CustomerCsv.HEADER);
        header.set(0, "full_name");
        header.set(1, "external_id");

        assertThatThrownBy(() -> CustomerCsv.checkHeader(header))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CSV header");
    }
}
