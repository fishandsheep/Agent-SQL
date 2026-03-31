package com.sqlagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public class SqlAgentApplication {

    private final Environment environment;

    public SqlAgentApplication(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] args) {
        SpringApplication.run(SqlAgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printAccessUrl() {
        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String localUrl = "http://localhost:" + port + contextPath;

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            log.info("[startup] application ready: localUrl={}", localUrl);
            log.info("[startup] application ready: hostUrl=http://{}:{}{}", localHost.getHostAddress(), port, contextPath);
            log.info("[startup] application ready: hostNameUrl=http://{}:{}{}", localHost.getHostName(), port, contextPath);
        } catch (UnknownHostException e) {
            log.info("[startup] application ready: localUrl={}", localUrl);
            log.warn("[startup] unable to resolve host information: {}", e.getMessage());
        }
    }
}