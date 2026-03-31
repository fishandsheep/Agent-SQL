package com.sqlagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
public class DatabaseConnectionManager {

    @Value("${spring.datasource.default.url}")
    private String defaultUrl;

    @Value("${spring.datasource.default.username}")
    private String defaultUsername;

    @Value("${spring.datasource.default.password}")
    private String defaultPassword;

    @Value("${spring.datasource.default.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String defaultDriverClassName;

    private volatile HikariDataSource defaultDataSource;

    public DataSource getDefaultDataSource() {
        HikariDataSource cached = defaultDataSource;
        if (cached != null && !cached.isClosed()) {
            return cached;
        }

        synchronized (this) {
            cached = defaultDataSource;
            if (cached != null && !cached.isClosed()) {
                return cached;
            }

            log.info("Creating default datasource: {}", defaultUrl);
            HikariDataSource created = createDefaultHikariDataSource();
            defaultDataSource = created;
            return created;
        }
    }

    private HikariDataSource createDefaultHikariDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(defaultUrl);
        hikariConfig.setUsername(defaultUsername);
        hikariConfig.setPassword(defaultPassword);
        hikariConfig.setDriverClassName(defaultDriverClassName);
        configureHikariPool(hikariConfig);
        return new HikariDataSource(hikariConfig);
    }

    private void configureHikariPool(HikariConfig config) {
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(240000);
        config.setKeepaliveTime(120000);
        config.setValidationTimeout(3000);
        config.setConnectionTestQuery("SELECT 1");
        config.setPoolName("SQL-Agent-HikariPool-" + System.currentTimeMillis());
    }

    @PreDestroy
    public void closeAllConnections() {
        HikariDataSource cached = defaultDataSource;
        if (cached != null && !cached.isClosed()) {
            log.info("Closing default datasource");
            cached.close();
        }
        defaultDataSource = null;
    }
}
