package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.DDLResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 获取表结构和索引信息工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DDLTool {

    private final DatabaseConnectionManager databaseConnectionManager;

    /**
     * 获取表的DDL信息
     *
     * @param tableName 表名
     * @return DDL信息JSON
     */
    public String getDDL(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            return "{\"error\": \"表名为空\"}";
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            DDLResult result = new DDLResult();
            result.setTableName(tableName);

            // 获取表结构
            result.setTableStructure(getTableStructure(conn, tableName));

            // 获取索引信息
            result.setIndexes(getIndexes(conn, tableName));

            log.debug("获取 DDL 成功: table={}", tableName);

            // 转换为JSON返回
            return convertToJson(result);

        } catch (SQLException e) {
            log.error("获取 DDL 失败: {}", e.getMessage(), e);
            return String.format("{\"error\": \"数据库错误: %s\"}", e.getMessage());
        }
    }

    /**
     * 将DDLResult转换为JSON字符串
     */
    private String convertToJson(DDLResult result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("转换JSON失败", e);
            return "{\"error\": \"JSON转换失败\"}";
        }
    }

    /**
     * 获取表结构信息
     */
    private String getTableStructure(Connection conn, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 表结构 ===\n");

        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, " +
                     "COLUMN_KEY, COLUMN_DEFAULT, EXTRA " +
                     "FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                     "ORDER BY ORDINAL_POSITION";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("DATA_TYPE");
                String columnType = rs.getString("COLUMN_TYPE");
                String isNullable = rs.getString("IS_NULLABLE");
                String columnKey = rs.getString("COLUMN_KEY");
                String columnDefault = rs.getString("COLUMN_DEFAULT");
                String extra = rs.getString("EXTRA");

                sb.append(String.format("- %s %s", columnName, columnType));

                if ("NO".equals(isNullable)) {
                    sb.append(" NOT NULL");
                }
                if ("PRI".equals(columnKey)) {
                    sb.append(" PRIMARY KEY");
                }
                if ("UNI".equals(columnKey)) {
                    sb.append(" UNIQUE");
                }
                if ("auto_increment".equals(extra)) {
                    sb.append(" AUTO_INCREMENT");
                }
                if (columnDefault != null) {
                    sb.append(" DEFAULT '").append(columnDefault).append("'");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取索引信息
     */
    private String getIndexes(Connection conn, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 索引信息 ===\n");

        String sql = "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE " +
                     "FROM INFORMATION_SCHEMA.STATISTICS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                     "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            String currentIndex = null;
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                String indexType = rs.getString("INDEX_TYPE");
                boolean nonUnique = rs.getInt("NON_UNIQUE") == 1;

                if (!indexName.equals(currentIndex)) {
                    if (currentIndex != null) {
                        sb.append("\n");
                    }
                    currentIndex = indexName;
                    sb.append(String.format("- %s (%s)", indexName, indexType));
                    if ("PRIMARY".equals(indexName)) {
                        sb.append(" [主键]");
                    } else if (nonUnique) {
                        sb.append(" [普通索引]");
                    } else {
                        sb.append(" [唯一索引]");
                    }
                    sb.append("\n  列: ");
                } else {
                    sb.append(", ");
                }
                sb.append(columnName);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private DDLResult createBadResult(String error) {
        DDLResult result = new DDLResult();
        result.setTableName("ERROR");
        result.setTableStructure(error);
        result.setIndexes("");
        return result;
    }
}
