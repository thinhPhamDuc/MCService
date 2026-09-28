package com.app.importservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Test chạy trên MySQL thật (cùng bản 8.4 với docker compose) trong 1 container tạm.
 * {@link ServiceConnection} tự trỏ spring.datasource.* vào container này. Cần Docker đang chạy.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4");
    }
}
