package com.sqlagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.engine.PlanExecutionEngine;
import com.sqlagent.evaluator.PlanDiversityChecker;
import com.sqlagent.evaluator.PlanEvaluator;
import com.sqlagent.generator.PlanGenerator;
import com.sqlagent.gateway.ToolGateway;
import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.EvaluationReport;
import com.sqlagent.model.ExplainResult;
import com.sqlagent.model.MultiPlanRequest;
import com.sqlagent.model.MultiPlanResponse;
import com.sqlagent.model.OptimizationPlan;
import com.sqlagent.model.PlanCandidate;
import com.sqlagent.model.PlanExecutionResult;
import com.sqlagent.model.ToolCallLog;
import com.sqlagent.tools.DDLTool;
import com.sqlagent.tools.ExplainTool;
import com.sqlagent.tools.SqlPatternAdvisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedOptimizationService {

    private final PlanGenerator planGenerator;
    private final PlanExecutionEngine executionEngine;
    private final PlanEvaluator planEvaluator;
    private final ToolGateway toolGateway;
    private final PlanDiversityChecker diversityChecker;
    private final HistoryService historyService;
    private final ExecutionTraceService executionTraceService;
    private final OptimizationResponseAssembler responseAssembler;
    private final ExplainTool explainTool;
    private final DDLTool ddlTool;
    private final SqlPatternAdvisor sqlPatternAdvisor;
    private final ObjectMapper objectMapper;

    public MultiPlanResponse optimize(MultiPlanRequest request) {
        return optimize(request, UUID.randomUUID().toString().substring(0, 8));
    }

    public MultiPlanResponse optimize(MultiPlanRequest request, String sessionId) {
        log.info("[UnifiedOptimizationService] start optimize: sessionId={}", sessionId);

        try {
            executionTraceService.recordPhase(
                sessionId,
                "request_received",
                "接收优化请求",
                "SUCCESS",
                "系统已接收 SQL 优化请求",
                "system",
                truncate(request != null ? request.getSql() : null, 180),
                "开始准备执行链路",
                0L
            );

            // 快速路径判断：对于简单主键查询直接返回，避免调用 AI
            MultiPlanResponse fastPathResponse = tryFastPath(request, sessionId);
            if (fastPathResponse != null) {
                log.info("[UnifiedOptimizationService] fast path matched for sessionId={}", sessionId);
                return fastPathResponse;
            }

            List<PlanCandidate> candidates = planGenerator.generatePlans(
                request.getSql(),
                sessionId,
                request.getModel(),
                request.getMaxPlans()
            );

            if (candidates.isEmpty()) {
                executionTraceService.recordPhase(
                    sessionId,
                    "plan_generation",
                    "生成候选方案",
                    "FAILED",
                    "未生成有效候选方案",
                    "agent",
                    truncate(request.getSql(), 180),
                    "候选方案为空",
                    0L
                );
                return responseAssembler.buildEmptyResponse("Agent 未生成任何优化方案");
            }

            boolean diverse = candidates.size() <= 1 || diversityChecker.isDiverse(candidates);
            executionTraceService.recordPhase(
                sessionId,
                "diversity_check",
                "检查候选方案多样性",
                diverse ? "SUCCESS" : "FAILED",
                diverse ? "候选方案差异度通过" : "候选方案差异度不足",
                "inferred",
                "candidateCount=" + candidates.size(),
                diverse ? "继续执行验证" : "建议补充不同策略的候选方案",
                0L
            );
            if (!diverse) {
                log.warn("[UnifiedOptimizationService] candidate plans are not diverse enough");
            }

            List<PlanExecutionResult> results = executionEngine.executePlans(
                request.getSql(),
                candidates,
                sessionId
            );

            executionTraceService.recordPhase(
                sessionId,
                "evaluation",
                "评估并选择最优方案",
                "RUNNING",
                "系统正在对候选方案打分并选择最优方案",
                "system",
                "candidateCount=" + candidates.size(),
                "等待评分结果",
                0L
            );
            EvaluationReport report = planEvaluator.evaluate(candidates, results);
            executionTraceService.recordPhase(
                sessionId,
                "evaluation",
                "评估并选择最优方案",
                "SUCCESS",
                "最优方案已确定",
                "system",
                "candidateCount=" + candidates.size(),
                "bestPlanId=" + report.getBestPlanId(),
                0L
            );
            executionTraceService.recordStep(sessionId, responseAssembler.buildSelectionStep(report));

            List<ToolCallLog> toolLogs = toolGateway.getSessionLogs(sessionId);
            MultiPlanResponse response = responseAssembler.buildResponse(request, candidates, results, report, toolLogs, sessionId);
            historyService.saveHistory(response);
            return response;
        } catch (Exception e) {
            log.error("[UnifiedOptimizationService] optimize failed: sessionId={}", sessionId, e);
            executionTraceService.recordPhase(
                sessionId,
                "request_failed",
                "优化任务失败",
                "FAILED",
                "优化流程异常结束",
                "system",
                truncate(request != null ? request.getSql() : null, 180),
                e.getMessage(),
                0L
            );
            return responseAssembler.buildErrorResponse("优化失败: " + e.getMessage());
        } finally {
            toolGateway.clearSession(sessionId);
            executionTraceService.clearSession(sessionId);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }

    /**
     * 快速路径判断：对于简单主键查询直接返回，避免调用 AI
     *
     * 判断条件：
     * - EXPLAIN type 为 const 或 system
     * - rows <= 1
     * - key 存在（有索引）
     *
     * 如果满足条件，直接构建"无需优化"响应返回
     *
     * @param request 优化请求
     * @param sessionId 会话 ID
     * @return 如果满足快速路径条件则返回响应，否则返回 null
     */
    private MultiPlanResponse tryFastPath(MultiPlanRequest request, String sessionId) {
        long startTime = System.currentTimeMillis();

        try {
            String sql = request.getSql();
            if (sql == null || sql.isBlank()) {
                return null;
            }

            // 1. 执行 EXPLAIN
            String explainJson = explainTool.explain(sql);
            if (explainJson == null || explainJson.contains("\"error\"")) {
                log.debug("[FastPath] EXPLAIN 失败，跳过快速路径");
                return null;
            }

            ExplainResult explainResult = objectMapper.readValue(explainJson, ExplainResult.class);
            if (explainResult == null) {
                return null;
            }

            // 2. 判断是否满足快速路径条件
            String type = explainResult.getType();
            Long rows = explainResult.getRows();
            String key = explainResult.getKey();

            // 条件：type=const/system, rows<=1, key存在
            boolean isFastPath = ("const".equalsIgnoreCase(type) || "system".equalsIgnoreCase(type))
                && (rows != null && rows <= 1)
                && (key != null && !key.isBlank() && !"null".equalsIgnoreCase(key));

            if (!isFastPath) {
                log.debug("[FastPath] 不满足快速路径条件: type={}, rows={}, key={}", type, rows, key);
                return null;
            }

            log.info("[FastPath] 满足快速路径条件: type={}, rows={}, key={}", type, rows, key);

            // 3. 构建 PlanExecutionResult（基线结果）
            PlanExecutionResult baselineResult = PlanExecutionResult.builder()
                .planId("BASELINE")
                .explain(explainResult)
                .executionTime(1L)
                .resultEqual(true)
                .status(com.sqlagent.enums.ExecutionStatus.SUCCESS)
                .build();

            // 4. 如果有 SELECT *，展开为显式字段
            String optimizedSql = sql;
            SqlPatternAdvisor.Analysis analysis = sqlPatternAdvisor.analyze(sql, null);
            if (analysis.isHasSelectStar()) {
                // 提取表名
                String tableName = extractTableName(sql);
                if (tableName != null) {
                    String ddlJson = ddlTool.getDDL(tableName);
                    if (ddlJson != null && !ddlJson.contains("\"error\"")) {
                        String expandedSql = expandSelectStar(sql, tableName, ddlJson);
                        if (expandedSql != null) {
                            optimizedSql = expandedSql;
                            log.info("[FastPath] 已展开 SELECT * 为显式字段");
                        }
                    }
                }
            }

            // 5. 记录执行跟踪
            executionTraceService.recordPhase(
                sessionId,
                "fast_path_check",
                "快速路径检查",
                "SUCCESS",
                "检测到简单主键查询，跳过 AI 分析",
                "system",
                "type=" + type + ", rows=" + rows + ", key=" + key,
                "直接返回优化结果",
                System.currentTimeMillis() - startTime
            );

            // 6. 构建完整的响应（使用 assembler 方法）
            MultiPlanResponse response = responseAssembler.buildFastPathResponse(
                request,
                baselineResult,
                optimizedSql,
                sessionId,
                System.currentTimeMillis() - startTime
            );

            // 7. 保存历史记录
            historyService.saveHistory(response);

            return response;

        } catch (Exception e) {
            log.warn("[FastPath] 快速路径判断失败，继续正常流程: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 SQL 中提取表名（简单实现）
     */
    private String extractTableName(String sql) {
        String lowerSql = sql.toLowerCase();
        int fromIndex = lowerSql.indexOf("from");
        if (fromIndex < 0) {
            return null;
        }

        String afterFrom = sql.substring(fromIndex + 4).trim();
        // 提取表名（忽略可能的反引号）
        String tableName = afterFrom.split("\\s+")[0].replaceAll("[`']", "");
        return tableName;
    }

    /**
     * 展开 SELECT * 为显式字段列表
     */
    private String expandSelectStar(String sql, String tableName, String ddlJson) {
        try {
            // 从 DDL 中解析出字段名
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"COLUMN_NAME\"\\s*:\\s*\"([^\"]+)\""
            );
            java.util.regex.Matcher matcher = pattern.matcher(ddlJson);

            java.util.List<String> columns = new java.util.ArrayList<>();
            while (matcher.find()) {
                columns.add(matcher.group(1));
            }

            if (columns.isEmpty()) {
                return null;
            }

            // 替换 SELECT * 为显式字段
            String columnsStr = String.join(", ", columns);
            return sql.replaceAll("(?i)select\\s+\\*\\s+from", "SELECT " + columnsStr + " FROM");

        } catch (Exception e) {
            log.warn("[FastPath] 展开 SELECT * 失败: {}", e.getMessage());
            return null;
        }
    }
}
