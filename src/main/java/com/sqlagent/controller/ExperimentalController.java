package com.sqlagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.config.RuntimeFeatureProperties;
import com.sqlagent.model.ApplyOptimizationResponse;
import com.sqlagent.model.MultiPlanResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExperimentalController {

    private final DatabaseConnectionManager databaseConnectionManager;
    private final RuntimeFeatureProperties runtimeFeatureProperties;
    private final ObjectMapper objectMapper;

    @PostMapping("/apply-optimization")
    public ResponseEntity<ApplyOptimizationResponse> applyOptimization(@RequestBody Map<String, Object> request) {
        if (!runtimeFeatureProperties.getFeatures().isMutationEndpointsEnabled()) {
            return ResponseEntity.status(403).body(ApplyOptimizationResponse.builder()
                .success(false)
                .error("当前运行模式为只读演示，已禁用真实索引变更")
                .build());
        }

        String planId = (String) request.get("planId");
        String sessionId = (String) request.get("sessionId");

        log.info("[apply-optimization] request received: planId={}, sessionId={}", planId, sessionId);

        if (planId == null || planId.isEmpty()) {
            return ResponseEntity.ok(ApplyOptimizationResponse.builder()
                .success(false)
                .error("方案ID不能为空")
                .build());
        }

        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            String indexDDL = null;

            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(buildHistoryLookupSql(sessionId))) {

                if (sessionId != null && !sessionId.isBlank()) {
                    ps.setString(1, sessionId);
                }

                var rs = ps.executeQuery();
                if (rs.next()) {
                    String analysisResultJson = rs.getString("analysis_result");
                    if (analysisResultJson != null) {
                        MultiPlanResponse multiPlanResponse = objectMapper.readValue(analysisResultJson, MultiPlanResponse.class);
                        if (multiPlanResponse.getPlans() != null) {
                            for (var plan : multiPlanResponse.getPlans()) {
                                if (planId.equals(plan.getPlanId()) && plan.getIndexDDL() != null) {
                                    indexDDL = plan.getIndexDDL();
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (indexDDL == null || indexDDL.trim().isEmpty()) {
                return ResponseEntity.ok(ApplyOptimizationResponse.builder()
                    .success(false)
                    .error("该方案不包含索引优化")
                    .build());
            }

            String indexName = extractIndexNameFromDDL(indexDDL);
            log.info("[apply-optimization] creating index: {}", indexName);
            executeRealIndexDDL(indexDDL);

            return ResponseEntity.ok(ApplyOptimizationResponse.builder()
                .success(true)
                .createdIndexName(indexName)
                .indexDDL(indexDDL)
                .message(String.format("索引 %s 创建成功", indexName))
                .build());
        } catch (Exception e) {
            log.error("[apply-optimization] index creation failed", e);
            return ResponseEntity.ok(ApplyOptimizationResponse.builder()
                .success(false)
                .error("索引创建失败: " + e.getMessage())
                .build());
        }
    }

    private String extractIndexNameFromDDL(String ddl) {
        String upper = ddl.toUpperCase().trim();
        if (!upper.startsWith("CREATE INDEX")) {
            return "unknown_index";
        }

        String afterCreate = upper.substring("CREATE INDEX".length()).trim();
        if (afterCreate.startsWith("IF NOT EXISTS")) {
            afterCreate = afterCreate.substring("IF NOT EXISTS".length()).trim();
        }

        int onIndex = afterCreate.indexOf(" ON");
        if (onIndex == -1) {
            return "unknown_index";
        }

        return afterCreate.substring(0, onIndex).trim();
    }

    private String buildHistoryLookupSql(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return "SELECT analysis_result FROM sql_analysis_history WHERE session_id = ? ORDER BY id DESC LIMIT 1";
        }
        return "SELECT analysis_result FROM sql_analysis_history ORDER BY id DESC LIMIT 1";
    }

    private void executeRealIndexDDL(String ddl) throws Exception {
        var dataSource = databaseConnectionManager.getDefaultDataSource();
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }
}
