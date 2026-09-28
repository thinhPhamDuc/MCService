package com.app.importservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportServiceApplicationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesSchema() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);

        assertThat(tables).contains("import_job", "import_row", "customer");
    }

    @Test
    void runsOnJava25() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
    }
}
