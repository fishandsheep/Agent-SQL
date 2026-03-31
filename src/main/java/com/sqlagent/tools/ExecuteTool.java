package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.ExecuteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 执行SQL并获取性能数据工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteTool {

    private final DatabaseConnectionManager databaseConnectionManager;

    /**
     * 执行SQL，获取行数和执行时间
     *
     * @param sql SQL语句
     * @return 执行结果JSON
     */
    public String execute(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "{\"error\": \"SQL为空\"}";
        }

        // 验证字段是否存在（快速失败）
        String validationError = validateColumnsExist(sql);
        if (validationError != null) {
            log.warn("SQL字段验证失败: {}", validationError);
            return String.format("{\"error\": \"字段验证失败: %s\", \"success\": false}", validationError);
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            log.debug("执行SQL: {}", sql);

            // 执行查询并计时
            long startTime = System.nanoTime();
            ResultSet rs = ps.executeQuery();
            long endTime = System.nanoTime();

            // 计算执行时间（毫秒）
            long executionTimeMs = (endTime - startTime) / 1_000_000;

            // 统计行数
            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
            }

            log.debug("SQL执行成功: rows={}, time={}ms", rowCount, executionTimeMs);

            ExecuteResult result = new ExecuteResult();
            result.setSql(sql);
            result.setRowCount(rowCount);
            result.setExecutionTimeMs(executionTimeMs);
            result.setSuccess(true);
            result.setError(null);

            // 转换为JSON返回
            return convertToJson(result);

        } catch (SQLException e) {
            log.error("执行SQL失败: {}", e.getMessage(), e);
            return String.format("{\"error\": \"数据库错误: %s\"}", e.getMessage());
        }
    }

    /**
     * 将ExecuteResult转换为JSON字符串
     */
    private String convertToJson(ExecuteResult result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("转换JSON失败", e);
            return "{\"error\": \"JSON转换失败\"}";
        }
    }

    private ExecuteResult createBadResult(String error) {
        ExecuteResult result = new ExecuteResult();
        result.setSql("ERROR");
        result.setRowCount(0);
        result.setExecutionTimeMs(0);
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    /**
     * 验证SQL中的字段是否存在
     * 快速失败，避免执行不存在的字段
     *
     * @param sql SQL语句
     * @return 错误信息，如果验证通过则返回null
     */
    private String validateColumnsExist(String sql) {
        try {
            // 提取表名和字段名
            java.util.Set<String> tableNames = extractTableNames(sql);
            if (tableNames.isEmpty()) {
                return null; // 无法提取表名，跳过验证
            }

            java.util.Set<String> columns = extractSelectColumns(sql);
            if (columns.isEmpty() || columns.contains("*")) {
                return null; // SELECT * 或无法提取字段，跳过验证
            }

            // 查询数据库验证字段是否存在
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                for (String tableName : tableNames) {
                    java.util.Set<String> existingColumns = getTableColumns(conn, tableName);

                    for (String column : columns) {
                        // 去掉表名前缀（如 user.id -> id）
                        String columnName = column;
                        if (column.contains(".")) {
                            columnName = column.substring(column.indexOf('.') + 1);
                        }
                        // 去掉反引号
                        columnName = columnName.replaceAll("[`']", "");

                        // 检查字段是否存在
                        if (!existingColumns.contains(columnName) && !columnName.equals("*")) {
                            // 尝试找到相似的列名
                            String suggestion = findSimilarColumn(columnName, existingColumns);
                            if (suggestion != null) {
                                return String.format("字段 '%s' 在表 '%s' 中不存在。您是否想用 '%s'？", columnName, tableName, suggestion);
                            } else {
                                return String.format("字段 '%s' 在表 '%s' 中不存在。表中可用的字段: %s", columnName, tableName, String.join(", ", existingColumns));
                            }
                        }
                    }
                }
            }

            return null; // 验证通过
        } catch (Exception e) {
            log.warn("字段验证失败，跳过验证: {}", e.getMessage());
            return null; // 验证失败时跳过，不影响执行
        }
    }

    /**
     * 提取SQL中的表名
     */
    private java.util.Set<String> extractTableNames(String sql) {
        java.util.Set<String> tables = new java.util.HashSet<>();
        String lowerSql = sql.toLowerCase();

        // 简单匹配 FROM 和 JOIN 后面的表名
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?:from|join)\\s+([`']?[\\w]+[`']?)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.regex.Matcher matcher = pattern.matcher(lowerSql);
        while (matcher.find()) {
            String table = matcher.group(1).replaceAll("[`']", "");
            if (!table.equalsIgnoreCase("dual")) {
                tables.add(table);
            }
        }

        return tables;
    }

    /**
     * 提取SELECT列表中的字段名
     */
    private java.util.Set<String> extractSelectColumns(String sql) {
        java.util.Set<String> columns = new java.util.HashSet<>();

        // 提取SELECT和FROM之间的部分
        int selectIndex = sql.toLowerCase().indexOf("select");
        int fromIndex = sql.toLowerCase().indexOf("from");

        if (selectIndex < 0 || fromIndex < 0 || selectIndex > fromIndex) {
            return columns;
        }

        String selectPart = sql.substring(selectIndex + 6, fromIndex).trim();

        // 如果是SELECT *，直接返回
        if (selectPart.equals("*")) {
            columns.add("*");
            return columns;
        }

        // 分割字段（处理逗号）
        String[] parts = selectPart.split(",");
        for (String part : parts) {
            String column = part.trim().split("\\s+")[0]; // 去掉AS别名
            // 去掉函数调用，只保留字段名
            if (!column.contains("(")) {
                // 去掉反引号
                column = column.replaceAll("[`']", "");
                columns.add(column);
            }
        }

        return columns;
    }

    /**
     * 获取表的所有字段名
     */
    private java.util.Set<String> getTableColumns(Connection conn, String tableName) throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";

        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }

        return columns;
    }

    /**
     * 查找相似的字段名（用于错误提示）
     */
    private String findSimilarColumn(String target, java.util.Set<String> candidates) {
        String targetLower = target.toLowerCase();

        // 精确匹配（忽略大小写）
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(target)) {
                return candidate;
            }
        }

        // 包含匹配
        for (String candidate : candidates) {
            if (candidate.toLowerCase().contains(targetLower) || targetLower.contains(candidate.toLowerCase())) {
                return candidate;
            }
        }

        // 常见时间字段映射
        if (targetLower.contains("create") || targetLower.contains("created")) {
            for (String candidate : candidates) {
                if (candidate.toLowerCase().contains("time") || candidate.toLowerCase().contains("register")) {
                    return candidate;
                }
            }
        }

        return null;
    }
}
