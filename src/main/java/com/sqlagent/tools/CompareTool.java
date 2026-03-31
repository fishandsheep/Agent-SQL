package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.CompareResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compare two SQL result sets while being explicit about whether the conclusion is exact.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompareTool {

    static final int MAX_COMPARE_ROWS = 1000;
    static final int MAX_EXACT_COMPARE_ROWS = 10000;
    static final int MAX_DIFF_ROWS = 50;
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\border\\s+by\\b");

    private final DatabaseConnectionManager databaseConnectionManager;

    public String compare(String sql1, String sql2) {
        if (sql1 == null || sql2 == null) {
            return "{\"error\": \"SQL不能为空\"}";
        }

        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            QuerySnapshot result1 = executeSnapshot(conn, sql1);
            QuerySnapshot result2 = executeSnapshot(conn, sql2);
            ComparisonAssessment assessment = assessConsistency(result1, result2);

            Set<String> onlyInSql1 = diffRows(result1, result2);
            Set<String> onlyInSql2 = diffRows(result2, result1);

            log.info(
                "比较结果: consistent={}, reliable={}, mode={}, rows1={}, rows2={}, onlyIn1={}, onlyIn2={}",
                assessment.consistent(),
                assessment.reliable(),
                assessment.comparisonMode(),
                result1.getTotalRowCount(),
                result2.getTotalRowCount(),
                onlyInSql1.size(),
                onlyInSql2.size()
            );

            CompareResult result = new CompareResult();
            result.setSql1(sql1);
            result.setSql2(sql2);
            result.setRowCount1(safeInt(result1.getTotalRowCount()));
            result.setRowCount2(safeInt(result2.getTotalRowCount()));
            result.setConsistent(assessment.consistent());
            result.setReliable(assessment.reliable());
            result.setComparisonMode(assessment.comparisonMode());
            result.setNote(assessment.note());
            result.setOnlyInSql1(onlyInSql1);
            result.setOnlyInSql2(onlyInSql2);
            result.setSuccess(true);
            result.setError(null);
            return convertToJson(result);

        } catch (SQLException e) {
            log.error("比较SQL失败: {}", e.getMessage(), e);
            return convertToJson(createBadResult("数据库错误: " + e.getMessage()));
        }
    }

    private String convertToJson(CompareResult result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("转换JSON失败", e);
            return convertToJson(createBadResult("JSON转换失败"));
        }
    }

    private QuerySnapshot executeSnapshot(Connection conn, String sql) throws SQLException {
        String normalizedSql = normalizeSql(sql);
        long totalRows = countRows(conn, normalizedSql);
        int columnCount = resolveColumnCount(conn, normalizedSql);

        if (totalRows <= MAX_EXACT_COMPARE_ROWS) {
            RowCollection collection = fetchAllRows(conn, normalizedSql, columnCount);
            boolean truncated = totalRows > MAX_COMPARE_ROWS;
            return new QuerySnapshot(totalRows, collection.sampleRows(), collection.rowCounts(), truncated, true);
        }

        List<String> sampleRows = fetchSampleRows(conn, normalizedSql, columnCount);
        boolean truncated = totalRows > MAX_COMPARE_ROWS;
        if (truncated) {
            log.warn("SQL返回行数超过精确比较阈值，改为稳定样本比较: totalRows={}, threshold={}", totalRows, MAX_EXACT_COMPARE_ROWS);
        }
        return new QuerySnapshot(totalRows, sampleRows, Collections.emptyMap(), truncated, false);
    }

    private long countRows(Connection conn, String sql) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") sql_agent_count";
        try (PreparedStatement ps = conn.prepareStatement(countSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    private List<String> fetchSampleRows(Connection conn, String sql, int columnCount) throws SQLException {
        String sampleSql = buildSampleSql(sql, columnCount);
        List<String> rows = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sampleSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(serializeRow(rs, columnCount));
            }
        }

        return rows;
    }

    private RowCollection fetchAllRows(Connection conn, String sql, int columnCount) throws SQLException {
        String querySql = "SELECT * FROM (" + sql + ") sql_agent_compare_exact";
        Map<String, Integer> rowCounts = new HashMap<>();
        List<String> sampleRows = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(querySql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String row = serializeRow(rs, columnCount);
                rowCounts.merge(row, 1, Integer::sum);
                if (sampleRows.size() < MAX_COMPARE_ROWS) {
                    sampleRows.add(row);
                }
            }
        }

        return new RowCollection(sampleRows, rowCounts);
    }

    private int resolveColumnCount(Connection conn, String sql) throws SQLException {
        String metadataSql = "SELECT * FROM (" + sql + ") sql_agent_meta LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(metadataSql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            return metaData.getColumnCount();
        }
    }

    private String buildSampleSql(String sql, int columnCount) {
        StringBuilder sb = new StringBuilder("SELECT * FROM (")
            .append(sql)
            .append(") sql_agent_compare");

        if (!hasOrderBy(sql) && columnCount > 0) {
            sb.append(" ORDER BY ");
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    sb.append(", ");
                }
                sb.append(i);
            }
        }

        sb.append(" LIMIT ").append(MAX_COMPARE_ROWS);
        return sb.toString();
    }

    private boolean hasOrderBy(String sql) {
        return ORDER_BY_PATTERN.matcher(normalizeSql(sql)).find();
    }

    private String normalizeSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String serializeRow(ResultSet rs, int columnCount) throws SQLException {
        StringBuilder row = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
            Object value = rs.getObject(i);
            row.append(value == null ? "NULL" : value.toString());
            if (i < columnCount) {
                row.append("|");
            }
        }
        return row.toString();
    }

    static ComparisonAssessment assessConsistency(QuerySnapshot result1, QuerySnapshot result2) {
        if (result1.getTotalRowCount() != result2.getTotalRowCount()) {
            return new ComparisonAssessment(
                false,
                true,
                "ROW_COUNT_MISMATCH",
                "结果行数不同，已确认改写后的语义不一致"
            );
        }

        if (result1.isExactCompared() && result2.isExactCompared()) {
            boolean consistent = result1.getRowCounts().equals(result2.getRowCounts());
            return new ComparisonAssessment(
                consistent,
                true,
                "EXACT",
                consistent ? "已完成全量多重集比较" : "已完成全量多重集比较，发现结果不一致"
            );
        }

        boolean consistent = result1.getSampleRows().equals(result2.getSampleRows());
        return new ComparisonAssessment(
            consistent,
            false,
            "SAMPLED",
            "结果集超过精确比较阈值，仅完成稳定样本比较；当前结论不应作为严格语义校验依据"
        );
    }

    private Set<String> diffRows(QuerySnapshot left, QuerySnapshot right) {
        if (left.isExactCompared() && right.isExactCompared()) {
            return diffRows(left.getRowCounts(), right.getRowCounts());
        }
        return diffRows(left.getSampleRows(), right.getSampleRows());
    }

    private Set<String> diffRows(List<String> leftRows, List<String> rightRows) {
        Map<String, Integer> rightCounts = new HashMap<>();
        for (String row : rightRows) {
            rightCounts.merge(row, 1, Integer::sum);
        }

        Set<String> diff = new LinkedHashSet<>();
        for (String row : leftRows) {
            int remaining = rightCounts.getOrDefault(row, 0);
            if (remaining > 0) {
                rightCounts.put(row, remaining - 1);
            } else {
                diff.add(row);
                if (diff.size() >= MAX_DIFF_ROWS) {
                    break;
                }
            }
        }
        return diff;
    }

    private Set<String> diffRows(Map<String, Integer> leftCounts, Map<String, Integer> rightCounts) {
        Map<String, Integer> remaining = new HashMap<>(rightCounts);
        Set<String> diff = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : leftCounts.entrySet()) {
            int rightCount = remaining.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > rightCount) {
                diff.add(entry.getKey());
                if (diff.size() >= MAX_DIFF_ROWS) {
                    break;
                }
            }
        }
        return diff;
    }

    private CompareResult createBadResult(String error) {
        CompareResult result = new CompareResult();
        result.setSql1("ERROR");
        result.setSql2("ERROR");
        result.setRowCount1(0);
        result.setRowCount2(0);
        result.setConsistent(false);
        result.setReliable(false);
        result.setComparisonMode("ERROR");
        result.setNote(error);
        result.setOnlyInSql1(Set.of());
        result.setOnlyInSql2(Set.of());
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    private int safeInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    static class QuerySnapshot {
        private final long totalRowCount;
        private final List<String> sampleRows;
        private final Map<String, Integer> rowCounts;
        private final boolean truncated;
        private final boolean exactCompared;

        QuerySnapshot(long totalRowCount,
                      List<String> sampleRows,
                      Map<String, Integer> rowCounts,
                      boolean truncated,
                      boolean exactCompared) {
            this.totalRowCount = totalRowCount;
            this.sampleRows = sampleRows;
            this.rowCounts = rowCounts;
            this.truncated = truncated;
            this.exactCompared = exactCompared;
        }

        public long getTotalRowCount() {
            return totalRowCount;
        }

        public List<String> getSampleRows() {
            return sampleRows;
        }

        public Map<String, Integer> getRowCounts() {
            return rowCounts;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public boolean isExactCompared() {
            return exactCompared;
        }
    }

    record RowCollection(List<String> sampleRows, Map<String, Integer> rowCounts) {
    }

    record ComparisonAssessment(boolean consistent, boolean reliable, String comparisonMode, String note) {
    }
}
