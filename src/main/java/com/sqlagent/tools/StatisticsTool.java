package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.ColumnStatistics;
import com.sqlagent.model.StatisticsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 统计信息分析工具
 *
 * 分析表的统计信息，包括行数、列基数、选择性等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsTool {

    private final DatabaseConnectionManager databaseConnectionManager;

    // 选择性阈值
    private static final double HIGH_SELECTIVITY_THRESHOLD = 0.5;   // 50%
    private static final double LOW_SELECTIVITY_THRESHOLD = 0.05;    // 5%
    private static final double HIGH_NULL_RATIO_THRESHOLD = 0.5;     // 50%

    /**
     * 分析表的统计信息
     *
     * @param tableName 表名
     * @return 统计信息结果
     */
    public StatisticsResult analyzeTableStatistics(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            StatisticsResult result = new StatisticsResult();
            result.setError("表名为空");
            return result;
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            StatisticsResult result = new StatisticsResult();
            result.setTableName(tableName);

            // 获取总行数
            Long totalRows = getTotalRows(conn, tableName);
            if (totalRows == null || totalRows == 0) {
                result.setError("无法获取表行数或表为空");
                return result;
            }
            result.setTotalRows(totalRows);

            // 获取列列表
            List<String> columns = getColumns(conn, tableName);
            if (columns.isEmpty()) {
                result.setError("无法获取表列信息");
                return result;
            }

            // 分析每列的统计信息
            List<ColumnStatistics> columnStats = new ArrayList<>();
            List<String> lowSelectivityColumns = new ArrayList<>();
            List<String> highSelectivityColumns = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<String> suggestions = new ArrayList<>();

            for (String column : columns) {
                ColumnStatistics colStat = analyzeColumn(conn, tableName, column, totalRows);
                columnStats.add(colStat);

                // 分类选择性
                if (colStat.getSelectivity() != null) {
                    if (colStat.getSelectivity() < LOW_SELECTIVITY_THRESHOLD) {
                        lowSelectivityColumns.add(column);
                    } else if (colStat.getSelectivity() > HIGH_SELECTIVITY_THRESHOLD) {
                        highSelectivityColumns.add(column);
                    }
                }

                // 检查空值比例过高
                if (colStat.getNullRatio() != null && colStat.getNullRatio() > HIGH_NULL_RATIO_THRESHOLD) {
                    warnings.add(String.format("列 %s 空值比例过高 (%.1f%%)", column, colStat.getNullRatio() * 100));
                }
            }

            result.setColumns(columnStats);
            result.setLowSelectivityColumns(lowSelectivityColumns);
            result.setHighSelectivityColumns(highSelectivityColumns);
            result.setWarnings(warnings);
            result.setSuggestions(generateSuggestions(lowSelectivityColumns, highSelectivityColumns, warnings));

            log.info("统计信息分析完成: table={}, columns={}, lowSelectivity={}",
                    tableName, columns.size(), lowSelectivityColumns.size());

            return result;

        } catch (SQLException e) {
            log.error("统计信息分析失败: {}", e.getMessage(), e);
            StatisticsResult result = new StatisticsResult();
            result.setTableName(tableName);
            result.setError("数据库错误: " + e.getMessage());
            return result;
        }
    }

    /**
     * 获取表总行数
     */
    private Long getTotalRows(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong("TABLE_ROWS");
            }
        }
        return null;
    }

    /**
     * 获取表的所有列名
     */
    private List<String> getColumns(Connection conn, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                     "ORDER BY ORDINAL_POSITION";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * 分析单列的统计信息
     */
    private ColumnStatistics analyzeColumn(Connection conn, String tableName, String columnName, long totalRows) {
        ColumnStatistics stat = new ColumnStatistics();
        stat.setColumnName(columnName);

        try {
            // 获取列的数据类型
            String dataType = getDataType(conn, tableName, columnName);
            stat.setDataType(dataType);

            // 跳过不适合分析的类型（如BLOB, TEXT等）
            if (shouldSkipType(dataType)) {
                stat.setRecommendation("数据类型不适合统计分析");
                return stat;
            }

            // 获取基数（DISTINCT值数量）
            Long cardinality = getCardinality(conn, tableName, columnName);
            if (cardinality != null) {
                stat.setCardinality(cardinality);

                // 计算选择性
                double selectivity = totalRows > 0 ? (double) cardinality / totalRows : 0;
                stat.setSelectivity(selectivity);

                // 设置选择性等级
                if (selectivity < LOW_SELECTIVITY_THRESHOLD) {
                    stat.setSelectivityLevel("LOW");
                    stat.setRecommendation("低选择性字段，不适合单独建索引");
                } else if (selectivity > HIGH_SELECTIVITY_THRESHOLD) {
                    stat.setSelectivityLevel("HIGH");
                    stat.setRecommendation("高选择性字段，适合建索引");
                } else {
                    stat.setSelectivityLevel("MEDIUM");
                    stat.setRecommendation("中等选择性，可考虑复合索引");
                }
            }

            // 获取空值统计
            Long nullCount = getNullCount(conn, tableName, columnName);
            if (nullCount != null) {
                stat.setNullCount(nullCount);
                double nullRatio = totalRows > 0 ? (double) nullCount / totalRows : 0;
                stat.setNullRatio(nullRatio);
            }

            // 获取示例值
            String sampleValue = getSampleValue(conn, tableName, columnName);
            stat.setSampleValue(sampleValue);

            // 获取平均长度（仅对字符串类型）
            if (dataType != null && (dataType.contains("char") || dataType.contains("text"))) {
                Double avgLength = getAvgLength(conn, tableName, columnName);
                stat.setAvgLength(avgLength);
            }

        } catch (SQLException e) {
            log.warn("分析列 {} 失败: {}", columnName, e.getMessage());
            stat.setRecommendation("分析失败: " + e.getMessage());
        }

        return stat;
    }

    /**
     * 获取列的数据类型
     */
    private String getDataType(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("DATA_TYPE");
            }
        }
        return null;
    }

    /**
     * 判断是否应该跳过该类型的分析
     */
    private boolean shouldSkipType(String dataType) {
        if (dataType == null) return false;
        String type = dataType.toLowerCase();
        return type.contains("blob") || type.contains("text") || type.contains("json");
    }

    /**
     * 获取列的基数（DISTINCT值数量）
     */
    private Long getCardinality(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = String.format("SELECT COUNT(DISTINCT `%s`) FROM `%s`", columnName, tableName);

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return null;
    }

    /**
     * 获取空值数量
     */
    private Long getNullCount(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = String.format("SELECT COUNT(*) FROM `%s` WHERE `%s` IS NULL", tableName, columnName);

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return null;
    }

    /**
     * 获取示例值（转换为字符串，避免Jackson序列化LocalDateTime等问题）
     */
    private String getSampleValue(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = String.format("SELECT `%s` FROM `%s` WHERE `%s` IS NOT NULL LIMIT 1",
                                   columnName, tableName, columnName);

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                Object value = rs.getObject(1);
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }

    /**
     * 获取平均长度
     */
    private Double getAvgLength(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = String.format("SELECT AVG(LENGTH(`%s`)) FROM `%s` WHERE `%s` IS NOT NULL",
                                   columnName, tableName, columnName);

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble(1);
            }
        }
        return null;
    }

    /**
     * 生成优化建议
     */
    private List<String> generateSuggestions(List<String> lowSelectivityColumns,
                                             List<String> highSelectivityColumns,
                                             List<String> warnings) {
        List<String> suggestions = new ArrayList<>();

        if (!lowSelectivityColumns.isEmpty()) {
            suggestions.add("低选择性字段（" + String.join(", ", lowSelectivityColumns) +
                          "）不适合单独建索引，建议考虑复合索引或其他优化策略");
        }

        if (!highSelectivityColumns.isEmpty()) {
            suggestions.add("高选择性字段（" + String.join(", ", highSelectivityColumns) +
                          "）适合建索引，可作为索引候选");
        }

        if (!warnings.isEmpty()) {
            suggestions.add("存在空值比例过高的列，建议检查数据质量");
        }

        if (suggestions.isEmpty()) {
            suggestions.add("统计信息正常，建议结合执行计划进一步分析");
        }

        return suggestions;
    }
}
