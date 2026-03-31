package com.sqlagent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.enums.ExecutionStatus;
import com.sqlagent.exception.SandboxException;
import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.CompareResult;
import com.sqlagent.model.ExecuteResult;
import com.sqlagent.model.ExplainResult;
import com.sqlagent.model.PlanCandidate;
import com.sqlagent.model.PlanExecutionResult;
import com.sqlagent.sandbox.IndexSandboxContext;
import com.sqlagent.sandbox.IndexSandboxManager;
import com.sqlagent.service.ExecutionTraceService;
import com.sqlagent.tools.CompareTool;
import com.sqlagent.tools.ExecuteTool;
import com.sqlagent.tools.ExplainTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 方案执行引擎
 *
 * 负责在系统层串行执行候选方案，并记录完整的工具/阶段轨迹。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExecutionEngine {

    private final IndexSandboxManager sandboxManager;
    private final ExplainTool explainTool;
    private final ExecuteTool executeTool;
    private final CompareTool compareTool;
    private final ObjectMapper objectMapper;
    private final ExecutionTraceService executionTraceService;

    public List<PlanExecutionResult> executePlans(
        String originalSql,
        List<PlanCandidate> candidates,
        String sessionId
    ) {
        log.info("[PlanExecutionEngine] executing {} candidates", candidates.size());

        executionTraceService.recordPhase(
            sessionId,
            "plan_execution",
            "执行候选方案",
            "RUNNING",
            "系统正在串行验证候选方案",
            "system",
            "candidateCount=" + candidates.size(),
            "开始准备沙箱和基线",
            0L
        );

        try {
            IndexSandboxContext sandbox = sandboxManager.createSandbox(sessionId);
            executionTraceService.recordPhase(
                sessionId,
                "sandbox",
                "创建执行沙箱",
                "SUCCESS",
                "临时索引沙箱已创建",
                "system",
                "sessionId=" + sessionId,
                "sandboxId=" + sandbox.getSandboxId(),
                0L
            );

            PlanExecutionResult baseline = measureBaseline(originalSql, sessionId);
            List<PlanExecutionResult> results = new ArrayList<>();
            results.add(baseline);

            for (PlanCandidate candidate : candidates) {
                results.add(executeCandidate(originalSql, candidate, sessionId));
            }

            try {
                int deleted = sandboxManager.cleanupSandbox(sessionId);
                executionTraceService.recordPhase(
                    sessionId,
                    "sandbox_cleanup",
                    "清理临时沙箱",
                    "SUCCESS",
                    "执行结束后清理临时索引",
                    "system",
                    "sessionId=" + sessionId,
                    "deletedIndexes=" + deleted,
                    0L
                );
            } catch (SandboxException e) {
                executionTraceService.recordPhase(
                    sessionId,
                    "sandbox_cleanup",
                    "清理临时沙箱",
                    "FAILED",
                    "沙箱清理失败",
                    "system",
                    "sessionId=" + sessionId,
                    e.getMessage(),
                    0L
                );
                log.error("[PlanExecutionEngine] cleanup failed", e);
            }

            executionTraceService.recordPhase(
                sessionId,
                "plan_execution",
                "执行候选方案",
                "SUCCESS",
                "候选方案执行完成",
                "system",
                "candidateCount=" + candidates.size(),
                "resultCount=" + results.size(),
                0L
            );
            return results;
        } catch (Exception e) {
            log.error("[PlanExecutionEngine] failed", e);
            sandboxManager.cleanupSandbox(sessionId);
            executionTraceService.recordPhase(
                sessionId,
                "plan_execution",
                "执行候选方案",
                "FAILED",
                "候选方案执行失败",
                "system",
                "candidateCount=" + candidates.size(),
                e.getMessage(),
                0L
            );
            throw new RuntimeException("方案执行失败: " + e.getMessage(), e);
        }
    }

    private PlanExecutionResult measureBaseline(String sql, String sessionId) {
        executionTraceService.recordPhase(
            sessionId,
            "baseline",
            "测量基线 SQL",
            "RUNNING",
            "测量原始 SQL 的执行计划与耗时",
            "system",
            truncate(sql, 180),
            "等待执行结果",
            0L
        );

        try {
            ExplainResult explain = executeExplain(sql, sessionId, "BASELINE", "baseline");
            ExecuteResult execute = executeSql(sql, sessionId, "BASELINE", "baseline");

            PlanExecutionResult result = PlanExecutionResult.builder()
                .planId("BASELINE")
                .status(ExecutionStatus.SUCCESS)
                .explain(explain)
                .executionTime(execute.getExecutionTimeMs())
                .resultEqual(true)
                .build();

            executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "原始 SQL 基线测量完成"));
            executionTraceService.recordPhase(
                sessionId,
                "baseline",
                "测量基线 SQL",
                "SUCCESS",
                "原始 SQL 测量完成",
                "system",
                truncate(sql, 180),
                "time=" + result.getExecutionTime() + "ms",
                result.getExecutionTime()
            );
            return result;
        } catch (Exception e) {
            log.error("[PlanExecutionEngine] baseline failed", e);
            executionTraceService.recordPhase(
                sessionId,
                "baseline",
                "测量基线 SQL",
                "FAILED",
                "原始 SQL 测量失败",
                "system",
                truncate(sql, 180),
                e.getMessage(),
                0L
            );
            return PlanExecutionResult.builder()
                .planId("BASELINE")
                .status(ExecutionStatus.FAILED)
                .error(e.getMessage())
                .build();
        }
    }

    private PlanExecutionResult executeCandidate(String originalSql, PlanCandidate candidate, String sessionId) {
        String planId = candidate.getPlanId();
        executionTraceService.recordPhase(
            sessionId,
            "candidate_execution_" + planId,
            "执行候选方案 " + planId,
            "RUNNING",
            "系统正在验证候选方案 " + planId,
            "system",
            "type=" + candidate.getType(),
            candidate.getDescription(),
            0L
        );

        List<String> tmpIndexNames = new ArrayList<>();
        String tmpIndexName = null;
        try {
            if (candidate.requiresIndex()) {
                long start = System.currentTimeMillis();
                tmpIndexNames = sandboxManager.executeIndexDDLs(sessionId, candidate.getIndexDDL());
                tmpIndexName = String.join(";", tmpIndexNames);
                recordSystemToolStep(
                    sessionId,
                    "manageIndex",
                    "创建临时索引",
                    "SUCCESS",
                    candidate.getIndexDDL(),
                    "createdIndex=" + tmpIndexName,
                    System.currentTimeMillis() - start
                );
            }

            String sqlToExecute = candidate.requiresRewrite() ? candidate.getOptimizedSql() : originalSql;
            ExplainResult explain = executeExplain(sqlToExecute, sessionId, planId, "candidate");
            ExecuteResult execute = executeSql(sqlToExecute, sessionId, planId, "candidate");
            if (!execute.isSuccess()) {
                PlanExecutionResult result = PlanExecutionResult.builder()
                    .planId(planId)
                    .status(ExecutionStatus.FAILED)
                    .explain(explain)
                    .executionTime(execute.getExecutionTimeMs())
                    .createdIndexName(tmpIndexName)
                    .error(execute.getError())
                    .build();
                executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "候选方案执行失败"));
                executionTraceService.recordPhase(
                    sessionId,
                    "candidate_execution_" + planId,
                    "执行候选方案" + planId,
                    "FAILED",
                    "候选方案执行失败",
                    "system",
                    "type=" + candidate.getType(),
                    execute.getError(),
                    result.getExecutionTime() != null ? result.getExecutionTime() : 0L
                );
                return result;
            }

            Boolean resultEqual = true;
            if (candidate.requiresRewrite()) {
                CompareResult compare = executeCompare(originalSql, sqlToExecute, sessionId, planId);
                if (compare.getError() != null) {
                    PlanExecutionResult result = PlanExecutionResult.builder()
                        .planId(planId)
                        .status(ExecutionStatus.FAILED)
                        .explain(explain)
                        .executionTime(execute.getExecutionTimeMs())
                        .createdIndexName(tmpIndexName)
                        .error(compare.getError())
                        .build();
                    executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "候选方案执行失败"));
                    executionTraceService.recordPhase(
                        sessionId,
                        "candidate_execution_" + planId,
                        "执行候选方案" + planId,
                        "FAILED",
                        "候选方案执行失败",
                        "system",
                        "type=" + candidate.getType(),
                        compare.getError(),
                        result.getExecutionTime() != null ? result.getExecutionTime() : 0L
                    );
                    return result;
                }
                if (Boolean.FALSE.equals(compare.getReliable())) {
                    resultEqual = compare.isConsistent() ? Boolean.TRUE : null;
                    log.warn("[PlanExecutionEngine] compare result is sampled only, skip hard validation: planId={}, mode={}, note={}",
                        planId,
                        compare.getComparisonMode(),
                        compare.getNote());
                } else {
                    resultEqual = compare.isConsistent();
                    if (!resultEqual) {
                        PlanExecutionResult result = PlanExecutionResult.builder()
                            .planId(planId)
                            .status(ExecutionStatus.VALIDATION_ERROR)
                            .explain(explain)
                            .executionTime(execute.getExecutionTimeMs())
                            .resultEqual(false)
                            .validationError("优化后的 SQL 结果与原始 SQL 不一致")
                            .createdIndexName(tmpIndexName)
                            .build();
                        executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "候选方案验证失败"));
                        executionTraceService.recordPhase(
                            sessionId,
                            "candidate_execution_" + planId,
                            "执行候选方案" + planId,
                            "VALIDATION_ERROR",
                            "候选方案结果校验失败",
                            "system",
                            "type=" + candidate.getType(),
                            result.getValidationError(),
                            result.getExecutionTime()
                        );
                        return result;
                    }
                }
            }

            PlanExecutionResult result = PlanExecutionResult.builder()
                .planId(planId)
                .status(ExecutionStatus.SUCCESS)
                .explain(explain)
                .executionTime(execute.getExecutionTimeMs())
                .resultEqual(resultEqual)
                .createdIndexName(tmpIndexName)
                .build();

            executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "候选方案执行成功"));
            executionTraceService.recordPhase(
                sessionId,
                "candidate_execution_" + planId,
                "执行候选方案 " + planId,
                "SUCCESS",
                "候选方案验证完成",
                "system",
                "type=" + candidate.getType(),
                "time=" + result.getExecutionTime() + "ms",
                result.getExecutionTime()
            );
            return result;
        } catch (Exception e) {
            log.error("[PlanExecutionEngine] candidate {} failed", planId, e);
            PlanExecutionResult result = PlanExecutionResult.builder()
                .planId(planId)
                .status(ExecutionStatus.FAILED)
                .createdIndexName(tmpIndexName)
                .error(e.getMessage())
                .build();
            executionTraceService.recordStep(sessionId, buildPlanSummaryStep(result, "候选方案执行失败"));
            executionTraceService.recordPhase(
                sessionId,
                "candidate_execution_" + planId,
                "执行候选方案 " + planId,
                "FAILED",
                "候选方案执行失败",
                "system",
                "type=" + candidate.getType(),
                e.getMessage(),
                0L
            );
            return result;
        } finally {
            if (!tmpIndexNames.isEmpty()) {
                try {
                    long start = System.currentTimeMillis();
                    for (String createdIndex : tmpIndexNames) {
                        sandboxManager.dropIndex(sessionId, createdIndex);
                    }
                    recordSystemToolStep(
                        sessionId,
                        "manageIndex",
                        "删除临时索引",
                        "SUCCESS",
                        "index=" + tmpIndexName,
                        "deleted",
                        System.currentTimeMillis() - start
                    );
                } catch (Exception e) {
                    recordSystemToolStep(
                        sessionId,
                        "manageIndex",
                        "删除临时索引",
                        "FAILED",
                        "index=" + tmpIndexName,
                        e.getMessage(),
                        0L
                    );
                    log.error("[PlanExecutionEngine] drop temp index failed: {}", tmpIndexName, e);
                }
            }
        }
    }

    private ExplainResult executeExplain(String sql, String sessionId, String planId, String scope) throws Exception {
        long start = System.currentTimeMillis();
        String explainJson = explainTool.explain(sql);
        ExplainResult explain = objectMapper.readValue(explainJson, ExplainResult.class);
        recordSystemToolStep(
            sessionId,
            "explainPlan",
            "分析执行计划",
            "SUCCESS",
            planScope(scope, planId),
            buildExplainSummary(explain),
            System.currentTimeMillis() - start
        );
        return explain;
    }

    private ExecuteResult executeSql(String sql, String sessionId, String planId, String scope) throws Exception {
        long start = System.currentTimeMillis();
        String executeJson = executeTool.execute(sql);
        ExecuteResult execute = objectMapper.readValue(executeJson, ExecuteResult.class);
        recordSystemToolStep(
            sessionId,
            "executeSql",
            "执行 SQL",
            execute.isSuccess() ? "SUCCESS" : "FAILED",
            planScope(scope, planId),
            buildExecuteSummary(execute),
            System.currentTimeMillis() - start
        );
        return execute;
    }

    private CompareResult executeCompare(String originalSql, String optimizedSql, String sessionId, String planId) throws Exception {
        long start = System.currentTimeMillis();
        String compareJson = compareTool.compare(originalSql, optimizedSql);
        CompareResult compare = objectMapper.readValue(compareJson, CompareResult.class);
        recordSystemToolStep(
            sessionId,
            "compareResult",
            "校验结果一致性",
            compare.getError() == null
                ? (compare.isConsistent() ? "SUCCESS" : "VALIDATION_ERROR")
                : "FAILED",
            planScope("candidate", planId),
            "consistent=" + compare.isConsistent() + ", reliable=" + compare.getReliable() + ", mode=" + compare.getComparisonMode() + ", rows=" + compare.getRowCount1() + "/" + compare.getRowCount2(),
            System.currentTimeMillis() - start
        );
        return compare;
    }

    private void recordSystemToolStep(
        String sessionId,
        String toolName,
        String stepName,
        String status,
        String inputSummary,
        String outputSummary,
        long duration
    ) {
        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("tool_call");
        step.setStepName(stepName);
        step.setSourceType("system");
        step.setDataSource("tool");
        step.setStatus(status);
        step.setToolName(toolName);
        step.setDescription(stepName);
        step.setInputSummary(inputSummary);
        step.setOutputSummary(outputSummary);
        step.setExecutionTimeMs(duration);
        long end = System.currentTimeMillis();
        step.setStartTime(Math.max(0L, end - Math.max(0L, duration)));
        step.setEndTime(end);
        step.setTimestamp(end);
        executionTraceService.recordStep(sessionId, step);
    }

    private AgentExecutionStep buildPlanSummaryStep(PlanExecutionResult result, String description) {
        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("plan_execution");
        step.setStepName("evaluatePlan");
        step.setSourceType("system");
        step.setDataSource("system");
        step.setStatus(result.getStatus() != null ? result.getStatus().name() : (result.isValid() ? "SUCCESS" : "FAILED"));
        step.setToolName(result.getPlanId());
        step.setDescription(description);
        step.setInputSummary("planId=" + result.getPlanId());
        step.setOutputSummary(buildPlanResultSummary(result));
        step.setDetails(buildPlanDetails(result));
        step.setExplain(result.getExplain());
        step.setExecutionTimeMs(result.getExecutionTime() != null ? result.getExecutionTime() : 0L);
        long end = System.currentTimeMillis();
        step.setStartTime(Math.max(0L, end - step.getExecutionTimeMs()));
        step.setEndTime(end);
        step.setTimestamp(end);
        return step;
    }

    private String buildPlanResultSummary(PlanExecutionResult result) {
        List<String> tags = new ArrayList<>();
        tags.add("status=" + result.getStatus());
        if (result.getExecutionTime() != null) {
            tags.add("time=" + result.getExecutionTime() + "ms");
        }
        if (result.getExplain() != null && result.getExplain().getRows() != null) {
            tags.add("rows=" + result.getExplain().getRows());
        }
        if (result.getExplain() != null && result.getExplain().getKey() != null) {
            tags.add("key=" + result.getExplain().getKey());
        }
        if (result.getResultEqual() != null) {
            tags.add("resultEqual=" + result.getResultEqual());
        }
        return String.join(" | ", tags);
    }

    private String buildPlanDetails(PlanExecutionResult result) {
        List<String> details = new ArrayList<>();
        if (result.getCreatedIndexName() != null && !result.getCreatedIndexName().isBlank()) {
            details.add("createdIndex=" + result.getCreatedIndexName());
        }
        if (result.getValidationError() != null && !result.getValidationError().isBlank()) {
            details.add(result.getValidationError());
        }
        if (result.getError() != null && !result.getError().isBlank()) {
            details.add(result.getError());
        }
        return details.isEmpty() ? null : String.join("; ", details);
    }

    private String buildExplainSummary(ExplainResult explain) {
        if (explain == null) {
            return "无 EXPLAIN 结果";
        }
        return String.format(
            "type=%s, key=%s, rows=%s",
            safe(explain.getType()),
            safe(explain.getKey()),
            explain.getRows() != null ? explain.getRows() : "-"
        );
    }

    private String buildExecuteSummary(ExecuteResult execute) {
        if (execute == null) {
            return "无执行结果";
        }
        return String.format(
            "rows=%s, time=%sms, success=%s",
            execute.getRowCount(),
            execute.getExecutionTimeMs(),
            execute.isSuccess()
        );
    }

    private String planScope(String scope, String planId) {
        return scope + ", planId=" + planId;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
