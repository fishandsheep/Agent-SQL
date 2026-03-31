package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.DataSkewResult;
import com.sqlagent.model.ValueDistribution;
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
 * 数据倾斜分析工具
 *
 * 检测字段的数据分布倾斜，找出热点值
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSkewTool {

    private final DatabaseConnectionManager databaseConnectionManager;

    // 倾斜阈值
    private static final double SEVERE_SKEW_THRESHOLD = 0.8;   // 80%
    private static final double MODERATE_SKEW_THRESHOLD = 0.5; // 50%
    private static final int TOP_VALUES_LIMIT = 10;            // 分析Top 10值

    /**
     * 分析数据倾斜
     *
     * @param tableName 表名
     * @param columnName 列名
     * @return 数据倾斜分析结果
     */
    public DataSkewResult analyzeDataSkew(String tableName, String columnName) {
        DataSkewResult result = new DataSkewResult();
        result.setTableName(tableName);
        result.setColumnName(columnName);

        if (tableName == null || tableName.trim().isEmpty()) {
            result.setError("表名为空");
            return result;
        }

        if (columnName == null || columnName.trim().isEmpty()) {
            result.setError("列名为空");
            return result;
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            // 获取总行数
            long totalRows = getTotalRows(conn, tableName);
            if (totalRows == 0) {
                result.setError("表为空");
                return result;
            }
            result.setTotalRows(totalRows);

            // 获取值分布
            List<ValueDistribution> topValues = getValueDistribution(conn, tableName, columnName, TOP_VALUES_LIMIT);
            if (topValues.isEmpty()) {
                result.setError("无法获取值分布");
                return result;
            }
            result.setTopValues(topValues);

            // 计算倾斜系数和等级
            if (!topValues.isEmpty()) {
                double topValuePercentage = topValues.get(0).getPercentage() / 100.0;
                result.setSkewFactor(topValuePercentage);

                String skewLevel;
                if (topValuePercentage >= SEVERE_SKEW_THRESHOLD) {
                    skewLevel = DataSkewResult.SKEW_SEVERE;
                } else if (topValuePercentage >= MODERATE_SKEW_THRESHOLD) {
                    skewLevel = DataSkewResult.SKEW_MODERATE;
                } else {
                    skewLevel = DataSkewResult.SKEW_NONE;
                }
                result.setSkewLevel(skewLevel);

                // 生成建议
                result.setSuggestions(generateSuggestions(result, topValues));
            }

            log.info("数据倾斜分析完成: table={}, column={}, skewLevel={}",
                    tableName, columnName, result.getSkewLevel());

        } catch (SQLException e) {
            log.error("数据倾斜分析失败: {}", e.getMessage(), e);
            result.setError("数据库错误: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取表总行数
     */
    private long getTotalRows(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong("TABLE_ROWS");
            }
        }
        return 0;
    }

    /**
     * 获取值分布（Top N）
     */
    private List<ValueDistribution> getValueDistribution(Connection conn, String tableName,
                                                        String columnName, int limit) throws SQLException {
        List<ValueDistribution> distributions = new ArrayList<>();

        String sql = String.format(
                "SELECT `%s`, COUNT(*) as cnt FROM `%s` " +
                "WHERE `%s` IS NOT NULL " +
                "GROUP BY `%s` " +
                "ORDER BY cnt DESC " +
                "LIMIT %d",
                columnName, tableName, columnName, columnName, limit);

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Object value = rs.getObject(1);
                long count = rs.getLong(2);

                ValueDistribution dist = new ValueDistribution();
                dist.setValue(value);
                dist.setCount(count);

                distributions.add(dist);
            }
        }

        // 计算百分比
        if (!distributions.isEmpty()) {
            long totalRows = getTotalRows(conn, tableName);
            for (ValueDistribution dist : distributions) {
                double percentage = totalRows > 0 ? (dist.getCount() * 100.0 / totalRows) : 0;
                dist.setPercentage(percentage);
                dist.setHotspot(percentage >= 50); // 超过50%认为是热点值
            }
        }

        return distributions;
    }

    /**
     * 生成优化建议
     */
    private List<String> generateSuggestions(DataSkewResult result, List<ValueDistribution> topValues) {
        List<String> suggestions = new ArrayList<>();

        String skewLevel = result.getSkewLevel();

        switch (skewLevel) {
            case DataSkewResult.SKEW_SEVERE:
                suggestions.add("⚠️ 严重数据倾斜：Top值占比超过80%");
                suggestions.add("建议处理方案：");
                suggestions.add("1. 考虑使用分区表，按热点值分区");
                suggestions.add("2. 对热点值使用缓存，减轻数据库压力");
                suggestions.add("3. 避免在倾斜字段上建索引（索引效果差）");
                suggestions.add("4. 考虑使用bitmap索引（如适用）");
                break;

            case DataSkewResult.SKEW_MODERATE:
                suggestions.add("⚠️ 中度数据倾斜：Top值占比50-80%");
                suggestions.add("建议关注点：");
                suggestions.add("1. 监控该字段的查询性能");
                suggestions.add("2. 评估是否需要针对热点值优化");
                suggestions.add("3. 谨慎在该字段上建索引");
                break;

            case DataSkewResult.SKEW_NONE:
                suggestions.add("✅ 数据分布均匀，无明显倾斜");
                suggestions.add("该字段适合建索引（如其他条件满足）");
                break;
        }

        // 添加热点值信息
        if (!topValues.isEmpty() && topValues.get(0).isHotspot()) {
            ValueDistribution hotspot = topValues.get(0);
            suggestions.add(String.format("热点值: %s (占比%.1f%%, 出现%d次)",
                    hotspot.getValue(), hotspot.getPercentage(), hotspot.getCount()));
        }

        return suggestions;
    }
}
