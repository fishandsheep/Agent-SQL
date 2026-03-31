package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.ExplainResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 执行计划分析工具
 *
 * 功能：
 * - 连接到真实数据库执行 EXPLAIN 命令
 * - 解析 EXPLAIN 结果并返回JSON格式
 * - 支持动态数据源和内置默认数据源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExplainTool {

    private final DatabaseConnectionManager databaseConnectionManager;

    /**
     * 分析 SQL 执行计划
     *
     * @param sql      SQL 字符串
     * @return 执行计划结果JSON
     */
    public String explain(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "{\"error\": \"SQL 为空\"}";
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql)) {

            log.debug("执行 EXPLAIN: {}", sql);
            ResultSet rs = ps.executeQuery();

            // 解析所有 EXPLAIN 结果行（支持多表查询）
            java.util.List<ExplainResult> allRows = new java.util.ArrayList<>();
            while (rs.next()) {
                ExplainResult row = parseExplainResult(rs);
                allRows.add(row);
            }

            ExplainResult representativeRow;
            if (allRows.isEmpty()) {
                representativeRow = createBadResult("EXPLAIN 无结果返回");
            } else {
                representativeRow = copyResult(selectRepresentativeRow(allRows));
            }

            // 如果有多行，将其余执行计划行序列化为 JSON 字符串，保留给上层展示/诊断。
            if (allRows.size() > 1) {
                java.util.List<ExplainResult> additionalRows = new java.util.ArrayList<>(allRows);
                additionalRows.removeIf(row -> isSamePlanRow(row, representativeRow));
                String additionalRowsJson = convertToJson(additionalRows);
                representativeRow.setAdditionalRows(additionalRowsJson);
            }

            // 转换为JSON返回
            return convertToJson(representativeRow);

        } catch (SQLException e) {
            log.error("执行 EXPLAIN 失败: {}", e.getMessage(), e);
            return String.format("{\"error\": \"数据库错误: %s\"}", e.getMessage());
        }
    }

    /**
     * 将ExplainResult转换为JSON字符串
     */
    private String convertToJson(ExplainResult result) {
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
     * 将List<ExplainResult>转换为JSON字符串
     */
    private String convertToJson(java.util.List<ExplainResult> results) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        } catch (Exception e) {
            log.error("转换JSON失败", e);
            return "[]";
        }
    }

    private ExplainResult selectRepresentativeRow(java.util.List<ExplainResult> rows) {
        ExplainResult best = rows.get(0);
        double bestScore = calculateRepresentativeWeight(best);
        for (int i = 1; i < rows.size(); i++) {
            ExplainResult current = rows.get(i);
            double currentScore = calculateRepresentativeWeight(current);
            if (currentScore > bestScore) {
                best = current;
                bestScore = currentScore;
            }
        }
        return best;
    }

    private double calculateRepresentativeWeight(ExplainResult row) {
        if (row == null) {
            return Double.NEGATIVE_INFINITY;
        }

        double weight = 0.0;
        weight += accessTypePenalty(row.getType());
        weight += rowPenalty(row.getRows());

        String extra = row.getExtra() != null ? row.getExtra().toLowerCase() : "";
        if (extra.contains("using temporary")) {
            weight += 20;
        }
        if (extra.contains("using filesort")) {
            weight += 15;
        }
        if (Boolean.TRUE.equals(row.isHasProblem())) {
            weight += 15;
        }

        String table = row.getTable() != null ? row.getTable().trim().toLowerCase() : "";
        if (table.startsWith("<derived")) {
            weight -= 12;
        } else if (table.startsWith("<subquery")) {
            weight -= 8;
        } else if (!table.isBlank()) {
            weight += 5;
        }

        return weight;
    }

    private int accessTypePenalty(String type) {
        if (type == null) {
            return 20;
        }
        return switch (type.toUpperCase()) {
            case "ALL" -> 60;
            case "INDEX" -> 45;
            case "RANGE" -> 32;
            case "INDEX_MERGE" -> 28;
            case "REF", "REF_OR_NULL" -> 22;
            case "EQ_REF" -> 15;
            case "CONST", "SYSTEM" -> 5;
            default -> 20;
        };
    }

    private double rowPenalty(Long rows) {
        if (rows == null || rows <= 0) {
            return 0;
        }
        if (rows >= 1_000_000) {
            return 50;
        }
        if (rows >= 100_000) {
            return 40;
        }
        if (rows >= 10_000) {
            return 30;
        }
        if (rows >= 1_000) {
            return 20;
        }
        if (rows >= 100) {
            return 10;
        }
        if (rows >= 10) {
            return 5;
        }
        return 1;
    }

    private ExplainResult copyResult(ExplainResult source) {
        ExplainResult target = new ExplainResult();
        target.setId(source.getId());
        target.setSelectType(source.getSelectType());
        target.setTable(source.getTable());
        target.setPartitions(source.getPartitions());
        target.setType(source.getType());
        target.setPossibleKeys(source.getPossibleKeys());
        target.setKey(source.getKey());
        target.setKeyLen(source.getKeyLen());
        target.setRef(source.getRef());
        target.setRows(source.getRows());
        target.setFiltered(source.getFiltered());
        target.setExtra(source.getExtra());
        target.setScore(source.getScore());
        target.setHasProblem(source.isHasProblem());
        target.setAdditionalRows(source.getAdditionalRows());
        return target;
    }

    private boolean isSamePlanRow(ExplainResult left, ExplainResult right) {
        if (left == null || right == null) {
            return false;
        }
        return java.util.Objects.equals(left.getId(), right.getId())
            && java.util.Objects.equals(left.getSelectType(), right.getSelectType())
            && java.util.Objects.equals(left.getTable(), right.getTable())
            && java.util.Objects.equals(left.getType(), right.getType())
            && java.util.Objects.equals(left.getKey(), right.getKey())
            && java.util.Objects.equals(left.getRows(), right.getRows())
            && java.util.Objects.equals(left.getExtra(), right.getExtra());
    }

    /**
     * 解析 MySQL EXPLAIN 结果
     *
     * MySQL EXPLAIN 结果列：
     * - id: SELECT 标识符
     * - select_type: SELECT 类型
     * - table: 表名
     * - partitions: 分区
     * - type: 访问类型（ALL, index, range, ref, eq_ref, const, system）
     * - possible_keys: 可能使用的索引
     * - key: 实际使用的索引
     * - key_len: 使用的索引长度
     * - ref: 索引比较的列
     * - rows: 扫描的行数
     * - filtered: 过滤的行数百分比
     * - Extra: 额外信息
     */
    private ExplainResult parseExplainResult(ResultSet rs) throws SQLException {
        ExplainResult result = new ExplainResult();

        // 获取所有列
        result.setId(getLongOrNull(rs, "id"));
        result.setSelectType(rs.getString("select_type"));
        result.setTable(rs.getString("table"));
        result.setPartitions(rs.getString("partitions"));

        String type = rs.getString("type");
        result.setType(type != null ? type : "UNKNOWN");

        result.setPossibleKeys(rs.getString("possible_keys"));
        result.setKey(rs.getString("key"));
        result.setKeyLen(rs.getString("key_len"));
        result.setRef(rs.getString("ref"));

        Long rows = getLongOrNull(rs, "rows");
        result.setRows(rows != null ? rows : 0L);

        result.setFiltered(getDoubleOrNull(rs, "filtered"));

        String extra = rs.getString("Extra");
        result.setExtra(extra != null ? extra : "");

        // 计算评分
        String key = result.getKey();
        int score = calculateScore(type, key, result.getRows(), extra);
        result.setScore(score);

        // 判断是否有问题
        boolean hasProblem = hasPerformanceProblem(type, result.getRows(), extra);
        result.setHasProblem(hasProblem);

        log.debug("EXPLAIN 结果: table={}, type={}, key={}, rows={}, score={}, hasProblem={}",
                result.getTable(), type, key, result.getRows(), score, hasProblem);

        // 【性能提示】当检测到性能问题时，给出executeSql调用时机提示
        if (hasProblem) {
            log.info("[explain-tool] performance issue detected: type={}, rows={}", type, result.getRows());
            log.info("[explain-tool] recommendation: call executeSql before index changes to capture a baseline");
            log.info("[explain-tool] recommendation: call manageIndex after the baseline if index changes are required");
            log.info("[explain-tool] recommendation: call executeSql again after changes for comparison");
        }

        return result;
    }

    /**
     * 获取 Long 值，处理 null 情况
     */
    private Long getLongOrNull(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    /**
     * 获取 Double 值，处理 null 情况
     */
    private Double getDoubleOrNull(ResultSet rs, String columnLabel) throws SQLException {
        double value = rs.getDouble(columnLabel);
        return rs.wasNull() ? null : value;
    }

    /**
     * 计算执行计划评分
     *
     * 评分标准：
     * - const/system: 100 分（主键等值查询）
     * - eq_ref: 95 分（唯一索引连接）
     * - ref: 85 分（非唯一索引）
     * - fulltext: 75 分（全文索引）
     * - ref_or_null: 70 分（索引 + IS NULL）
     * - index_merge: 65 分（索引合并）
     * - unique_subquery: 90 分（子查询唯一索引）
     * - index_subquery: 80 分（子查询非唯一索引）
     * - range: 60 分（范围查询）
     * - index: 40 分（全索引扫描）
     * - ALL: 10 分（全表扫描）
     *
     * 额外调整：
     * - rows > 10000: -10 分
     * - Using filesort: -15 分
     * - Using temporary: -20 分
     * - Using index: +10 分
     */
    private int calculateScore(String type, String key, long rows, String extra) {
        int baseScore = getBaseScoreByType(type);

        // 根据 rows 调整
        if (rows > 100000) {
            baseScore -= 20;
        } else if (rows > 10000) {
            baseScore -= 10;
        } else if (rows > 1000) {
            baseScore -= 5;
        }

        // 根据 Extra 调整
        if (extra != null) {
            if (extra.contains("Using filesort")) {
                baseScore -= 15;
            }
            if (extra.contains("Using temporary")) {
                baseScore -= 20;
            }
            if (extra.contains("Using index")) {
                baseScore += 10;
            }
        }

        // 确保分数在 0-100 范围内
        return Math.max(0, Math.min(100, baseScore));
    }

    /**
     * 根据 type 获取基础分数
     */
    private int getBaseScoreByType(String type) {
        if (type == null) {
            return 0;
        }

        switch (type.toUpperCase()) {
            case "const":
            case "system":
                return 100;
            case "eq_ref":
                return 95;
            case "ref":
                return 85;
            case "fulltext":
                return 75;
            case "ref_or_null":
                return 70;
            case "index_merge":
                return 65;
            case "unique_subquery":
                return 90;
            case "index_subquery":
                return 80;
            case "range":
                return 60;
            case "index":
                return 40;
            case "ALL":
                return 10;
            default:
                return 0;
        }
    }

    /**
     * 判断是否有性能问题
     */
    private boolean hasPerformanceProblem(String type, long rows, String extra) {
        // 全表扫描
        if ("ALL".equalsIgnoreCase(type)) {
            return true;
        }

        // 全索引扫描且行数较多
        if ("index".equalsIgnoreCase(type) && rows > 1000) {
            return true;
        }

        // 扫描行数过多
        if (rows > 50000) {
            return true;
        }

        // 使用文件排序或临时表
        if (extra != null) {
            if (extra.contains("Using filesort") || extra.contains("Using temporary")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 创建糟糕的执行计划
     */
    private ExplainResult createBadResult(String reason) {
        ExplainResult result = new ExplainResult();
        result.setId(null);
        result.setSelectType(null);
        result.setTable(null);
        result.setPartitions(null);
        result.setType("ERROR");
        result.setPossibleKeys(null);
        result.setKey(null);
        result.setKeyLen(null);
        result.setRef(null);
        result.setRows(Long.MAX_VALUE);
        result.setFiltered(null);
        result.setExtra("Error: " + reason);
        result.setScore(0);
        result.setHasProblem(true);
        return result;
    }

    /**
     * 计算执行计划评分（保持向后兼容）
     *
     * @param before 优化前的执行计划
     * @param after  优化后的执行计划
     * @return 改进百分比（优化后 - 优化前）
     */
    public int calculateImprovement(ExplainResult before, ExplainResult after) {
        int beforeScore = before.getScore();
        int afterScore = after.getScore();
        return afterScore - beforeScore;
    }
}
