package com.sqlagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.HistoryDetailResult;
import com.sqlagent.model.HistoryListResult;
import com.sqlagent.model.MultiPlanResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseConnectionManager databaseConnectionManager;
    private final ObjectMapper objectMapper;

    public void saveHistory(MultiPlanResponse response) {
        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            String analysisResultJson = objectMapper.writeValueAsString(response);

            try (Connection conn = dataSource.getConnection()) {
                saveWithExtendedColumnsOrFallback(conn, response, analysisResultJson);
            }
        } catch (JsonProcessingException e) {
            log.error("[history] serialize failed", e);
        } catch (SQLException e) {
            log.error("[history] save failed", e);
        }
    }

    private void saveWithExtendedColumnsOrFallback(Connection conn, MultiPlanResponse response, String analysisResultJson)
        throws SQLException {
        String extendedSql = """
            INSERT INTO sql_analysis_history (
                original_sql,
                optimized_sql,
                best_plan_id,
                strategy,
                improvement_percentage,
                baseline_execution_time_ms,
                optimized_execution_time_ms,
                baseline_rows,
                optimized_rows,
                total_time_ms,
                model_name,
                db_name,
                session_id,
                schema_version,
                analysis_result
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(extendedSql)) {
            bindSaveStatement(ps, response, analysisResultJson, true);
            ps.executeUpdate();
            return;
        } catch (SQLException e) {
            log.warn("[history] extended insert failed, fallback to legacy columns: {}", e.getMessage());
        }

        String legacySql = """
            INSERT INTO sql_analysis_history (
                original_sql,
                optimized_sql,
                best_plan_id,
                strategy,
                improvement_percentage,
                baseline_execution_time_ms,
                optimized_execution_time_ms,
                baseline_rows,
                optimized_rows,
                total_time_ms,
                model_name,
                db_name,
                analysis_result
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(legacySql)) {
            bindSaveStatement(ps, response, analysisResultJson, false);
            ps.executeUpdate();
        }
    }

    private void bindSaveStatement(PreparedStatement ps, MultiPlanResponse response, String analysisResultJson, boolean extended)
        throws SQLException {
        ps.setString(1, response.getOriginalSql());
        ps.setString(2, response.getBestPlanMetrics() != null ? response.getBestPlanMetrics().getSql() : null);
        ps.setString(3, response.getBestPlanId());
        ps.setString(4, response.getSummary() != null ? response.getSummary().getStrategy() : null);

        if (response.getImprovementPercentage() != null) ps.setDouble(5, response.getImprovementPercentage());
        else ps.setNull(5, java.sql.Types.DOUBLE);

        if (response.getBaselineExecutionTime() != null) ps.setLong(6, response.getBaselineExecutionTime());
        else ps.setNull(6, java.sql.Types.BIGINT);

        if (response.getBestPlanExecutionTime() != null) ps.setLong(7, response.getBestPlanExecutionTime());
        else ps.setNull(7, java.sql.Types.BIGINT);

        if (response.getBaselineMetrics() != null && response.getBaselineMetrics().getRows() != null) ps.setLong(8, response.getBaselineMetrics().getRows());
        else ps.setNull(8, java.sql.Types.BIGINT);

        if (response.getBestPlanMetrics() != null && response.getBestPlanMetrics().getRows() != null) ps.setLong(9, response.getBestPlanMetrics().getRows());
        else ps.setNull(9, java.sql.Types.BIGINT);

        if (response.getTotalTime() != null) ps.setLong(10, response.getTotalTime());
        else ps.setNull(10, java.sql.Types.BIGINT);

        ps.setString(11, response.getModelName());
        ps.setString(12, response.getDbName());

        if (extended) {
            ps.setString(13, response.getSessionId());
            ps.setString(14, response.getSchemaVersion());
            ps.setString(15, analysisResultJson);
        } else {
            ps.setString(13, analysisResultJson);
        }
    }

    public HistoryListResult getHistoryList(int limit, LocalDateTime dateFrom, LocalDateTime dateTo) {
        HistoryListResult result = new HistoryListResult();
        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                StringBuilder sql = new StringBuilder("""
                    SELECT id, original_sql, optimized_sql, best_plan_id, strategy,
                           improvement_percentage, baseline_execution_time_ms, optimized_execution_time_ms,
                           baseline_rows, optimized_rows, total_time_ms, model_name, created_at
                    FROM sql_analysis_history
                    WHERE 1=1
                    """);

                List<Object> params = new ArrayList<>();
                if (dateFrom != null) {
                    sql.append(" AND created_at >= ? ");
                    params.add(java.sql.Timestamp.valueOf(dateFrom));
                }
                if (dateTo != null) {
                    sql.append(" AND created_at <= ? ");
                    params.add(java.sql.Timestamp.valueOf(dateTo));
                }

                sql.append(" ORDER BY created_at DESC LIMIT ? ");
                params.add(limit);

                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        List<HistoryListResult.HistoryItem> histories = new ArrayList<>();
                        while (rs.next()) {
                            String originalSql = rs.getString("original_sql");
                            histories.add(HistoryListResult.HistoryItem.builder()
                                .id(rs.getLong("id"))
                                .originalSqlFirstLine(extractFirstLine(originalSql))
                                .originalSql(originalSql)
                                .optimizedSql(rs.getString("optimized_sql"))
                                .bestPlanId(rs.getString("best_plan_id"))
                                .strategy(rs.getString("strategy"))
                                .improvementPercentage(getNullableDouble(rs, "improvement_percentage"))
                                .baselineExecutionTime(getNullableLong(rs, "baseline_execution_time_ms"))
                                .optimizedExecutionTime(getNullableLong(rs, "optimized_execution_time_ms"))
                                .baselineRows(getNullableLong(rs, "baseline_rows"))
                                .optimizedRows(getNullableLong(rs, "optimized_rows"))
                                .totalTime(getNullableLong(rs, "total_time_ms"))
                                .optimizationSuccessful(rs.getString("best_plan_id") != null)
                                .modelName(rs.getString("model_name"))
                                .createdAt(rs.getTimestamp("created_at") != null
                                    ? rs.getTimestamp("created_at").toLocalDateTime().format(DATE_FORMATTER)
                                    : null)
                                .build());
                        }
                        result.setHistories(histories);
                        result.setSuccess(true);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("[history] list failed", e);
            result.setSuccess(false);
            result.setError("读取历史记录失败: " + e.getMessage());
        }
        return result;
    }

    public HistoryDetailResult getHistoryDetail(Long id) {
        HistoryDetailResult result = new HistoryDetailResult();
        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT analysis_result FROM sql_analysis_history WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            result.setSuccess(false);
                            result.setError("历史记录不存在: id=" + id);
                            return result;
                        }

                        String analysisResultJson = rs.getString("analysis_result");
                        MultiPlanResponse response = objectMapper.readValue(analysisResultJson, MultiPlanResponse.class);
                        result.setSuccess(true);
                        result.setAnalysis(response);
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[history] detail failed", e);
            result.setSuccess(false);
            result.setError("读取历史详情失败: " + e.getMessage());
            return result;
        }
    }

    public boolean deleteHistory(Long id) {
        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "DELETE FROM sql_analysis_history WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    return ps.executeUpdate() > 0;
                }
            }
        } catch (SQLException e) {
            log.error("[history] delete failed", e);
            return false;
        }
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private String extractFirstLine(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String normalized = sql.replace("\r\n", "\n").replace('\r', '\n');
        int idx = normalized.indexOf('\n');
        return (idx >= 0 ? normalized.substring(0, idx) : normalized).trim();
    }
}
