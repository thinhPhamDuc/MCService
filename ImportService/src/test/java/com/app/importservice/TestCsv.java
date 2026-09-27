package com.app.importservice;

import com.app.importservice.ingest.CustomerCsv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tạo file CSV khách hàng cho test. */
public final class TestCsv {

    public static final String HEADER = String.join(",", CustomerCsv.HEADER);

    private TestCsv() {
    }

    /** Dòng hợp lệ; cột address có dấu phẩy nên phải nằm trong "...". */
    public static String validLine(int i) {
        return "C-" + i + ",Customer " + i + ",customer" + i + "@example.com,0900" + i + ",1990-01-15,F,"
                + "\"12 Nguyen Trai, Q1\",Ho Chi Minh,VN,700000,ACME,Engineer,120000.50,2024-03-01,ACTIVE";
    }

    public static String invalidEmailLine(int i) {
        return validLine(i).replace("customer" + i + "@example.com", "not-an-email");
    }

    public static Path write(Path dir, String name, List<String> lines) throws IOException {
        return Files.write(dir.resolve(name), lines);
    }

    /** Header + {@code valid} dòng hợp lệ + {@code invalid} dòng sai email ở cuối. */
    public static List<String> lines(int valid, int invalid) {
        List<String> lines = new ArrayList<>(valid + invalid + 1);
        lines.add(HEADER);
        for (int i = 1; i <= valid; i++) {
            lines.add(validLine(i));
        }
        for (int i = valid + 1; i <= valid + invalid; i++) {
            lines.add(invalidEmailLine(i));
        }
        return lines;
    }
}
