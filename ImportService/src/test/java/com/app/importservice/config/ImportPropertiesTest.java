package com.app.importservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPropertiesTest {

    private static ImportProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("import", ImportProperties.class);
    }

    @Test
    void missingIngestConfigUsesDefaults() {
        ImportProperties.Ingest ingest = bind(Map.of()).ingest();

        assertThat(ingest).isEqualTo(new ImportProperties.Ingest(6, 1000, 10, 1, Duration.ofMinutes(10)));
    }

    @Test
    void configuredValueOverridesOnlyThatField() {
        ImportProperties.Ingest ingest = bind(Map.of("import.ingest.writers", "3")).ingest();

        assertThat(ingest.writers()).isEqualTo(3);
        assertThat(ingest.chunkSize()).isEqualTo(1000);
    }
}
