package com.app.importservice.ingest;

import com.app.importservice.TestcontainersConfiguration;
import com.app.importservice.config.ImportProperties;
import com.app.importservice.job.ImportJobRepository;
import com.app.importservice.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Đo thời gian ingest với nhiều cấu hình — KHÔNG chạy cùng test thường (mất ~15 phút).
 * Chạy:
 * <pre>
 * python3 scripts/gen-customers-csv.py 1000000 /tmp/customers-1m.csv --invalid-every 1000
 * BENCH_FILE=/tmp/customers-1m.csv ./gradlew test --tests '*IngestBenchmarkTest' --rerun -i | grep BENCH
 * </pre>
 * Mỗi cấu hình chạy {@link #REPEAT} lần trên bảng import_row rỗng, lấy trung vị (median) để bớt nhiễu.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BENCH_FILE", matches = ".+")
class IngestBenchmarkTest {

    private static final int REPEAT = 3;

    @Autowired
    private ImportJobRepository jobs;
    @Autowired
    private ImportRowRepository rows;
    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @Test
    void benchmark() throws Exception {
        Path source = Path.of(System.getenv("BENCH_FILE"));
        List<ImportProperties.Ingest> configs = new ArrayList<>();
        // 1) số writer (chunk 1000, queue 10)
        for (int writers : new int[]{1, 2, 3, 4, 6, 8, 12}) {
            configs.add(config(writers, 1000, 10));
        }
        // 2) kích thước chunk (6 writer, queue 10)
        for (int chunk : new int[]{100, 500, 2000, 5000}) {
            configs.add(config(6, chunk, 10));
        }
        // 3) sức chứa hàng đợi (6 writer, chunk 1000)
        for (int queue : new int[]{0, 1, 100}) {
            configs.add(config(6, 1000, queue));
        }

        System.out.println("BENCH | writers | chunk | queue | median ms | rows/s | runs ms");
        for (ImportProperties.Ingest config : configs) {
            long[] millis = new long[REPEAT];
            int total = 0;
            for (int i = 0; i < REPEAT; i++) {
                jdbc.execute("TRUNCATE TABLE import_row"); // mọi lần đo bắt đầu từ bảng rỗng
                Path file = Files.copy(source, dir.resolve("run.csv"));
                long jobId = jobs.create("bench");
                IngestService service = new IngestService(jobs, rows, new ImportProperties(dir, config, null, null));

                long start = System.nanoTime();
                service.run(jobId, file);
                millis[i] = (System.nanoTime() - start) / 1_000_000;

                var job = jobs.findById(jobId).orElseThrow();
                assertThat(job.status()).isEqualTo(JobStatus.PROCESSING);
                total = job.totalRows();
                service.shutdown();
            }
            long[] sorted = millis.clone();
            Arrays.sort(sorted);
            long median = sorted[REPEAT / 2];
            System.out.printf("BENCH | %d | %d | %d | %d | %d | %s%n", config.writers(), config.chunkSize(),
                    config.queueCapacity(), median, total * 1000L / median, Arrays.toString(millis));
        }
    }

    private static ImportProperties.Ingest config(int writers, int chunk, int queue) {
        return new ImportProperties.Ingest(writers, chunk, queue, 1, Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(30));
    }
}
