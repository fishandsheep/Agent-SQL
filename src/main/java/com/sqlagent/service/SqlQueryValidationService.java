package com.sqlagent.service;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.SqlValidationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SqlQueryValidationService {

    private final SqlInputValidationService sqlInputValidationService;
    private final DatabaseConnectionManager databaseConnectionManager;

    public SqlValidationResponse validateSingle(String sql, String fieldName) {
        SqlInputValidationService.ValidationResult inputResult =
            sqlInputValidationService.validateSingleReadOnlySql(sql, fieldName);
        if (!inputResult.isValid()) {
            return invalidResponse(List.of(SqlValidationResponse.Item.builder()
                .index(1)
                .sql(sql)
                .valid(false)
                .message(inputResult.getMessage())
                .build()));
        }

        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection connection = dataSource.getConnection()) {
                SqlValidationResponse.Item item = validateWithExplain(connection, sql, 1);
                return item.isValid() ? validResponse(List.of(item)) : invalidResponse(List.of(item));
            }
        } catch (SQLException e) {
            return invalidResponse(List.of(SqlValidationResponse.Item.builder()
                .index(1)
                .sql(sql)
                .valid(false)
                .message("数据库连接失败: " + sanitizeMessage(e.getMessage()))
                .build()));
        }
    }

    public SqlValidationResponse validateBatch(List<String> sqls, int maxCount) {
        if (sqls == null || sqls.isEmpty()) {
            return invalidResponse(List.of(SqlValidationResponse.Item.builder()
                .index(1)
                .valid(false)
                .message("SQL 列表不能为空")
                .build()));
        }
        if (sqls.size() > maxCount) {
            return invalidResponse(List.of(SqlValidationResponse.Item.builder()
                .index(maxCount + 1)
                .valid(false)
                .message("SQL 数量不能超过 " + maxCount + " 条")
                .build()));
        }

        List<SqlValidationResponse.Item> items = new ArrayList<>();
        for (int i = 0; i < sqls.size(); i++) {
            String sql = sqls.get(i);
            SqlInputValidationService.ValidationResult inputResult =
                sqlInputValidationService.validateSingleReadOnlySql(sql, "第 " + (i + 1) + " 条 SQL");
            if (!inputResult.isValid()) {
                items.add(SqlValidationResponse.Item.builder()
                    .index(i + 1)
                    .sql(sql)
                    .valid(false)
                    .message(inputResult.getMessage())
                    .build());
            }
        }
        if (!items.isEmpty()) {
            return invalidResponse(items);
        }

        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection connection = dataSource.getConnection()) {
                for (int i = 0; i < sqls.size(); i++) {
                    items.add(validateWithExplain(connection, sqls.get(i), i + 1));
                }
            }
        } catch (SQLException e) {
            return invalidResponse(List.of(SqlValidationResponse.Item.builder()
                .index(1)
                .valid(false)
                .message("数据库连接失败: " + sanitizeMessage(e.getMessage()))
                .build()));
        }

        boolean allValid = items.stream().allMatch(SqlValidationResponse.Item::isValid);
        return allValid ? validResponse(items) : invalidResponse(items);
    }

    private SqlValidationResponse.Item validateWithExplain(Connection connection, String sql, int index) {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + sql)) {
            statement.executeQuery();
            return SqlValidationResponse.Item.builder()
                .index(index)
                .sql(sql)
                .valid(true)
                .message("校验通过")
                .build();
        } catch (SQLException e) {
            return SqlValidationResponse.Item.builder()
                .index(index)
                .sql(sql)
                .valid(false)
                .message("SQL 语法或执行计划校验失败: " + sanitizeMessage(e.getMessage()))
                .build();
        }
    }

    private SqlValidationResponse validResponse(List<SqlValidationResponse.Item> items) {
        return SqlValidationResponse.builder()
            .success(true)
            .valid(true)
            .message("校验通过")
            .items(items)
            .build();
    }

    private SqlValidationResponse invalidResponse(List<SqlValidationResponse.Item> items) {
        String message = items.stream()
            .filter(item -> !item.isValid())
            .map(SqlValidationResponse.Item::getMessage)
            .findFirst()
            .orElse("SQL 校验失败");
        return SqlValidationResponse.builder()
            .success(true)
            .valid(false)
            .message(message)
            .items(items)
            .build();
    }

    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.replaceAll("[\\r\\n]+", " ").trim();
    }
}
