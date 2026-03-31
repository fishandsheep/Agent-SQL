package com.sqlagent.service;

import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.EvaluationReport;
import com.sqlagent.model.ExplainResult;
import com.sqlagent.model.MultiPlanRequest;
import com.sqlagent.model.MultiPlanResponse;
import com.sqlagent.model.OptimizationPlan;
import com.sqlagent.model.PlanCandidate;
import com.sqlagent.model.PlanExecutionResult;
import com.sqlagent.model.ToolCallLog;
import com.sqlagent.tools.SqlPatternAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OptimizationResponseAssembler {

    private final ExecutionTraceService executionTraceService;
    private final SqlPatternAdvisor sqlPatternAdvisor;
    private final OptimizationTraceAssembler optimizationTraceAssembler;

    public MultiPlanResponse buildResponse(
        MultiPlanRequest request,
        List<PlanCandidate> candidates,
        List<PlanExecutionResult> results,
        EvaluationReport report,
        List<ToolCallLog> toolLogs,
        String sessionId
    ) {
        List<OptimizationPlan> plans = candidates.stream()
            .map(candidate -> buildOptimizationPlan(
                candidate,
                findResult(results, candidate.getPlanId()),
                report.getBestPlanId()
            ))
            .toList();

        OptimizationPlan bestPlan = plans.stream()
            .filter(plan -> report.getBestPlanId() != null && report.getBestPlanId().equals(plan.getPlanId()))
            .findFirst()
            .orElse(null);

        PlanExecutionResult baselineResult = findResult(results, "BASELINE");
        PlanExecutionResult bestResult = report.getBestPlanId() != null
            ? findResult(results, report.getBestPlanId())
            : null;
        boolean noNeedOptimization = isNoNeedOptimizationScenario(request.getSql(), baselineResult, bestResult, report);
        String finalBestPlanId = noNeedOptimization ? null : report.getBestPlanId();
        String finalReason = noNeedOptimization ? buildNoNeedReason(request.getSql(), baselineResult) : report.getReason();
        List<OptimizationPlan> finalPlans = noNeedOptimization ? List.of() : plans;
        OptimizationPlan finalBestPlan = noNeedOptimization ? null : bestPlan;
        String displayOptimizedSql = determineDisplayOptimizedSql(request.getSql(), finalBestPlan);

        MultiPlanResponse.MetricSnapshot baselineMetrics = buildMetricSnapshot(
            "优化前",
            request.getSql(),
            baselineResult
        );
        MultiPlanResponse.MetricSnapshot bestPlanMetrics = buildMetricSnapshot(
            "优化后",
            displayOptimizedSql,
            bestResult
        );
        boolean rowsComparable = areComparableExplainRows(
            baselineResult != null ? baselineResult.getExplain() : null,
            bestResult != null ? bestResult.getExplain() : null
        );
        MultiPlanResponse.MetricSnapshot displayBaselineMetrics = sanitizeComparableRows(baselineMetrics, rowsComparable);
        MultiPlanResponse.MetricSnapshot displayBestPlanMetrics = sanitizeComparableRows(bestPlanMetrics, rowsComparable);

        List<AgentExecutionStep> executionSteps = executionTraceService.getSessionSteps(sessionId);
        if (executionSteps.isEmpty()) {
            executionSteps = optimizationTraceAssembler.buildExecutionSteps(results, toolLogs);
        }
        List<MultiPlanResponse.AnalysisStep> analysisSteps = optimizationTraceAssembler.buildAnalysisTimeline(executionSteps);
        long totalTime = executionSteps.stream().mapToLong(AgentExecutionStep::getExecutionTimeMs).sum();

        return MultiPlanResponse.builder()
            .success(true)
            .originalSql(request.getSql())
            .baselineSql(request.getSql())
            .optimizedSql(displayOptimizedSql)
            .plans(finalPlans)
            .bestPlanId(finalBestPlanId)
            .reason(finalReason)
            .summary(buildSummaryView(report, finalBestPlan, displayBaselineMetrics, displayBestPlanMetrics, noNeedOptimization))
            .analysis(buildAnalysis(finalBestPlan, finalPlans))
            .execution(executionSteps)
            .evidence(buildEvidence(finalPlans, displayBaselineMetrics, displayBestPlanMetrics))
            .baselineExplain(baselineResult != null ? baselineResult.getExplain() : null)
            .baselineMetrics(displayBaselineMetrics)
            .bestPlanMetrics(displayBestPlanMetrics)
            .comparison(buildComparison(displayBaselineMetrics, displayBestPlanMetrics))
            .coreMetrics(buildCoreMetrics(report, displayBaselineMetrics, displayBestPlanMetrics))
            .explainComparison(buildExplainComparison(finalPlans, baselineResult, bestResult))
            .planSelection(buildPlanSelectionSummary(finalBestPlanId, finalReason, finalPlans))
            .riskAssessment(buildRiskAssessment(finalBestPlan, report, request.getSql(), noNeedOptimization))
            .analysisTimeline(analysisSteps)
            .totalTime(totalTime)
            .baselineExecutionTime(report.getBaselineExecutionTime())
            .bestPlanExecutionTime(report.getBestPlanExecutionTime())
            .improvementPercentage(report.getImprovementPercentage())
            .outcomeStatus(noNeedOptimization ? "NO_NEED" : determineOutcomeStatus(report, finalBestPlan))
            .sessionId(sessionId)
            .modelName(request.getModel())
            .dbName(null)
            .schemaVersion("result-v2")
            .sqlRewriteAdvisory(buildSqlRewriteAdvisory(request.getSql(), finalBestPlan))
            .build();
    }

    private String determineDisplayOptimizedSql(String originalSql, OptimizationPlan bestPlan) {
        if (bestPlan != null && bestPlan.getOptimizedSql() != null && !bestPlan.getOptimizedSql().isBlank()) {
            return bestPlan.getOptimizedSql();
        }
        if (originalSql == null || originalSql.isBlank()) {
            return originalSql;
        }

        SqlPatternAdvisor.Analysis analysis = sqlPatternAdvisor.analyze(originalSql, null);
        if (analysis.isHasSelectStar()) {
            String projectionExpandedSql = sqlPatternAdvisor.expandSelectStarOnly(originalSql);
            if (projectionExpandedSql != null && !projectionExpandedSql.isBlank()) {
                return projectionExpandedSql;
            }
        }
        return originalSql;
    }

    private boolean isNoNeedOptimizationScenario(
        String originalSql,
        PlanExecutionResult baselineResult,
        PlanExecutionResult bestResult,
        EvaluationReport report
    ) {
        if (baselineResult == null || baselineResult.getExplain() == null) {
            return false;
        }
        ExplainResult baselineExplain = baselineResult.getExplain();
        if (baselineExplain.isHasProblem() || !hasEffectiveIndexAccess(baselineExplain)) {
            return false;
        }

        double improvement = report.getImprovementPercentage() != null ? report.getImprovementPercentage() : 0.0;
        long baselineTime = baselineResult.getExecutionTime() != null ? baselineResult.getExecutionTime() : 0L;
        long optimizedTime = bestResult != null && bestResult.getExecutionTime() != null
            ? bestResult.getExecutionTime()
            : baselineTime;
        long absoluteGain = Math.max(0L, baselineTime - optimizedTime);

        if (improvement >= 15.0 || absoluteGain >= 20L) {
            return false;
        }

        SqlPatternAdvisor.Analysis analysis = sqlPatternAdvisor.analyze(originalSql, null);
        return improvement <= 0.0 || analysis.isHasSelectStar();
    }

    private boolean hasEffectiveIndexAccess(ExplainResult explain) {
        String accessType = explain.getType();
        String key = explain.getKey();
        return accessType != null
            && !"ALL".equalsIgnoreCase(accessType)
            && key != null
            && !key.isBlank()
            && !"null".equalsIgnoreCase(key);
    }

    private String buildNoNeedReason(String originalSql, PlanExecutionResult baselineResult) {
        ExplainResult explain = baselineResult != null ? baselineResult.getExplain() : null;
        String key = explain != null ? explain.getKey() : null;
        String accessType = explain != null ? explain.getType() : null;
        Long executionTime = baselineResult != null ? baselineResult.getExecutionTime() : null;
        boolean hasSelectStar = sqlPatternAdvisor.analyze(originalSql, null).isHasSelectStar();

        StringBuilder builder = new StringBuilder("当前 SQL 已命中有效索引");
        if (key != null && !key.isBlank()) {
            builder.append(" ").append(key);
        }
        if (accessType != null && !accessType.isBlank()) {
            builder.append("，访问类型为 ").append(accessType);
        }
        if (executionTime != null) {
            builder.append("，基线耗时 ").append(executionTime).append("ms");
        }
        builder.append("，未观察到明确且稳定的性能收益，无需进一步优化");
        if (hasSelectStar) {
            builder.append("。可选建议：将 SELECT * 改为显式字段列表");
        }
        builder.append("。");
        return builder.toString();
    }

    private String determineOutcomeStatus(EvaluationReport report, OptimizationPlan bestPlan) {
        if (bestPlan == null) {
            return "FAILED";
        }
        if (bestPlan.isValid()) {
            return "SUCCESS";
        }
        if (report.getImprovementPercentage() != null && report.getImprovementPercentage() > 0) {
            return "PARTIAL_SUCCESS";
        }
        if (report.getImprovementPercentage() != null && report.getImprovementPercentage() <= 0) {
            return "NO_NEED";
        }
        return "FAILED";
    }

    private MultiPlanResponse.SqlRewriteAdvisory buildSqlRewriteAdvisory(String originalSql, OptimizationPlan bestPlan) {
        if (originalSql == null) {
            return null;
        }
        SqlPatternAdvisor.Analysis analysis = sqlPatternAdvisor.analyze(originalSql, null);

        boolean autoAppliedSelectStar = bestPlan != null
            && bestPlan.getOptimizedSql() != null
            && !bestPlan.getOptimizedSql().toLowerCase().contains("select *");
        boolean autoAppliedDateFunction = analysis.isHasDateFunctionPredicate()
            && bestPlan != null
            && bestPlan.getOptimizedSql() != null
            && !bestPlan.getOptimizedSql().toLowerCase().contains("date_format(");
        boolean autoAppliedImplicitConversion = analysis.isHasImplicitTypeConversion()
            && bestPlan != null
            && bestPlan.getOptimizedSql() != null
            && !bestPlan.getOptimizedSql().matches("(?is).*_id\\s*=\\s*'[-+]?\\d+'.*");

        boolean autoApplied = (!analysis.isHasSelectStar() || autoAppliedSelectStar)
            && (!analysis.isHasDateFunctionPredicate() || autoAppliedDateFunction)
            && (!analysis.isHasImplicitTypeConversion() || autoAppliedImplicitConversion);

        if (analysis.getDiagnostics().isEmpty()) {
            return MultiPlanResponse.SqlRewriteAdvisory.builder()
                .hasSelectStar(false)
                .hasDateFunctionPredicate(false)
                .hasImplicitTypeConversion(false)
                .autoApplied(false)
                .reason("未检测到显著的 SQL 改写提示")
                .build();
        }

        return MultiPlanResponse.SqlRewriteAdvisory.builder()
            .hasSelectStar(analysis.isHasSelectStar())
            .hasDateFunctionPredicate(analysis.isHasDateFunctionPredicate())
            .hasImplicitTypeConversion(analysis.isHasImplicitTypeConversion())
            .autoApplied(autoApplied)
            .suggestedSql(autoApplied && bestPlan != null ? bestPlan.getOptimizedSql() : analysis.getSuggestedSql())
            .reason(autoApplied
                ? "最优方案已经应用了已识别的重要 SQL 改写点"
                : "检测到可搜索性或类型匹配风险，建议结合提示继续改写 SQL")
            .issues(analysis.getDiagnostics())
            .build();
    }

    private MultiPlanResponse.CoreMetrics buildCoreMetrics(
        EvaluationReport report,
        MultiPlanResponse.MetricSnapshot baselineMetrics,
        MultiPlanResponse.MetricSnapshot bestPlanMetrics
    ) {
        return MultiPlanResponse.CoreMetrics.builder()
            .executionTimeMs(buildValueComparison(
                report.getBaselineExecutionTime(),
                report.getBestPlanExecutionTime(),
                "ms"
            ))
            .scannedRows(buildValueComparison(
                baselineMetrics != null ? baselineMetrics.getRows() : null,
                bestPlanMetrics != null ? bestPlanMetrics.getRows() : null,
                "rows"
            ))
            .improvementPercentage(buildValueComparison(
                0.0,
                report.getImprovementPercentage(),
                "%"
            ))
            .build();
    }

    private MultiPlanResponse.ValueComparison buildValueComparison(Number baseline, Number optimized, String unit) {
        if (baseline == null && optimized == null) {
            return null;
        }

        Double baselineValue = baseline != null ? baseline.doubleValue() : null;
        Double optimizedValue = optimized != null ? optimized.doubleValue() : null;
        Double delta = null;
        Double deltaPct = null;
        if (baselineValue != null && optimizedValue != null) {
            delta = baselineValue - optimizedValue;
            if (baselineValue != 0) {
                deltaPct = delta * 100.0 / baselineValue;
            }
        }
        return MultiPlanResponse.ValueComparison.builder()
            .baseline(baselineValue)
            .optimized(optimizedValue)
            .delta(delta)
            .deltaPercentage(deltaPct)
            .unit(unit)
            .build();
    }

    private MultiPlanResponse.ExplainComparison buildExplainComparison(
        List<OptimizationPlan> plans,
        PlanExecutionResult baselineResult,
        PlanExecutionResult bestResult
    ) {
        List<ExplainResult> candidates = plans.stream()
            .map(OptimizationPlan::getExplain)
            .filter(explain -> explain != null)
            .toList();

        List<String> diffSummary = new ArrayList<>();
        if (baselineResult != null && bestResult != null
            && baselineResult.getExplain() != null && bestResult.getExplain() != null) {
            diffSummary.add("type: " + safeValue(baselineResult.getExplain().getType())
                + " -> " + safeValue(bestResult.getExplain().getType()));
            diffSummary.add("key: " + safeValue(baselineResult.getExplain().getKey())
                + " -> " + safeValue(bestResult.getExplain().getKey()));
            if (areComparableExplainRows(baselineResult.getExplain(), bestResult.getExplain())) {
                diffSummary.add("rows: " + safeValue(baselineResult.getExplain().getRows())
                    + " -> " + safeValue(bestResult.getExplain().getRows()));
            } else {
                diffSummary.add("rows: not directly comparable across different explain nodes");
            }
            diffSummary.add("extra: " + safeValue(baselineResult.getExplain().getExtra())
                + " -> " + safeValue(bestResult.getExplain().getExtra()));
        }

        return MultiPlanResponse.ExplainComparison.builder()
            .baseline(baselineResult != null ? baselineResult.getExplain() : null)
            .optimized(bestResult != null ? bestResult.getExplain() : null)
            .candidates(candidates)
            .diffSummary(diffSummary)
            .build();
    }

    private MultiPlanResponse.PlanSelectionSummary buildPlanSelectionSummary(
        String selectedPlanId,
        String selectedReason,
        List<OptimizationPlan> plans
    ) {
        int validCount = (int) plans.stream().filter(OptimizationPlan::isValid).count();
        return MultiPlanResponse.PlanSelectionSummary.builder()
            .selectedPlanId(selectedPlanId)
            .selectedReason(selectedReason)
            .candidateCount(plans.size())
            .validCandidateCount(validCount)
            .build();
    }

    private boolean areComparableExplainRows(ExplainResult baselineExplain, ExplainResult bestExplain) {
        if (baselineExplain == null || bestExplain == null) {
            return false;
        }
        String baselineTable = normalizeIdentifier(baselineExplain.getTable());
        String bestTable = normalizeIdentifier(bestExplain.getTable());
        String baselineSelectType = normalizeIdentifier(baselineExplain.getSelectType());
        String bestSelectType = normalizeIdentifier(bestExplain.getSelectType());
        return !baselineTable.isEmpty()
            && baselineTable.equals(bestTable)
            && !baselineSelectType.isEmpty()
            && baselineSelectType.equals(bestSelectType);
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().replace("`", "").toLowerCase();
    }

    private MultiPlanResponse.RiskAssessment buildRiskAssessment(OptimizationPlan bestPlan,
                                                                 EvaluationReport report,
                                                                 String originalSql,
                                                                 boolean noNeedOptimization) {
        List<String> cautions = new ArrayList<>();
        if (bestPlan != null && bestPlan.getRejectionReason() != null) {
            cautions.add(bestPlan.getRejectionReason());
        }
        if (bestPlan != null && bestPlan.getValidationError() != null) {
            cautions.add(bestPlan.getValidationError());
        }
        cautions.addAll(sqlPatternAdvisor.analyze(originalSql, null).getDiagnostics());

        return MultiPlanResponse.RiskAssessment.builder()
            .level(bestPlan != null ? bestPlan.getRiskLevel() : null)
            .expectedBenefit(report.getImprovementPercentage() != null
                ? String.format("预计提升 %.1f%%", report.getImprovementPercentage())
                : null)
            .strategyNotes(noNeedOptimization
                ? "当前执行计划和耗时表现正常，无需继续做索引或结构性优化。"
                : bestPlan != null ? bestPlan.getReasoning() : null)
            .cautions(cautions)
            .build();
    }

    private MultiPlanResponse.SummaryInfo buildSummaryView(
        EvaluationReport report,
        OptimizationPlan bestPlan,
        MultiPlanResponse.MetricSnapshot baselineMetrics,
        MultiPlanResponse.MetricSnapshot bestPlanMetrics,
        boolean noNeedOptimization
    ) {
        String improvement = report.getImprovementPercentage() != null
            ? String.format("%.1f%%", report.getImprovementPercentage())
            : null;
        return MultiPlanResponse.SummaryInfo.builder()
            .improvement(improvement)
            .strategy(noNeedOptimization ? "无需优化" : bestPlan != null ? formatStrategyName(bestPlan.getType()) : "SQL 优化")
            .baselineExecutionTime(report.getBaselineExecutionTime())
            .optimizedExecutionTime(report.getBestPlanExecutionTime())
            .baselineRows(baselineMetrics != null ? baselineMetrics.getRows() : null)
            .optimizedRows(bestPlanMetrics != null ? bestPlanMetrics.getRows() : null)
            .selectedPlanSummary(noNeedOptimization ? "当前 SQL 已具备有效索引访问路径" : bestPlan != null ? bestPlan.getDescription() : null)
            .build();
    }

    private MultiPlanResponse.SummaryInfo buildSummary(
        EvaluationReport report,
        OptimizationPlan bestPlan,
        MultiPlanResponse.MetricSnapshot baselineMetrics,
        MultiPlanResponse.MetricSnapshot bestPlanMetrics,
        boolean noNeedOptimization
    ) {
        String improvement = report.getImprovementPercentage() != null
            ? String.format("%.1f%%", report.getImprovementPercentage())
            : null;
        return MultiPlanResponse.SummaryInfo.builder()
            .improvement(improvement)
            .strategy(bestPlan != null ? formatStrategyName(bestPlan.getType()) : "SQL 优化")
            .baselineExecutionTime(report.getBaselineExecutionTime())
            .optimizedExecutionTime(report.getBestPlanExecutionTime())
            .baselineRows(baselineMetrics != null ? baselineMetrics.getRows() : null)
            .optimizedRows(bestPlanMetrics != null ? bestPlanMetrics.getRows() : null)
            .selectedPlanSummary(bestPlan != null ? bestPlan.getDescription() : null)
            .build();
    }

    private MultiPlanResponse.AnalysisInfo buildAnalysis(OptimizationPlan bestPlan, List<OptimizationPlan> plans) {
        String tuningNotes = bestPlan != null && bestPlan.getIndexDDL() != null && bestPlan.getOptimizedSql() != null
            ? "该方案同时包含 SQL 改写与索引优化，适合完整展示优化链路"
            : null;
        return MultiPlanResponse.AnalysisInfo.builder()
            .bestPlanReasoning(bestPlan != null ? bestPlan.getReasoning() : null)
            .riskSummary(bestPlan != null ? bestPlan.getRiskLevel() : null)
            .tuningNotes(tuningNotes)
            .plans(plans)
            .build();
    }

    private MultiPlanResponse.MetricSnapshot buildMetricSnapshot(String label, String sql, PlanExecutionResult result) {
        if (result == null) {
            return MultiPlanResponse.MetricSnapshot.builder()
                .label(label)
                .sql(sql)
                .build();
        }

        ExplainResult explain = result.getExplain();
        return MultiPlanResponse.MetricSnapshot.builder()
            .label(label)
            .sql(sql)
            .explain(explain)
            .executionTimeMs(result.getExecutionTime())
            .rows(explain != null ? explain.getRows() : null)
            .scanType(explain != null ? explain.getType() : null)
            .keyName(explain != null ? explain.getKey() : null)
            .extra(explain != null ? explain.getExtra() : null)
            .build();
    }

    private MultiPlanResponse.MetricSnapshot sanitizeComparableRows(
        MultiPlanResponse.MetricSnapshot snapshot,
        boolean rowsComparable
    ) {
        if (snapshot == null || rowsComparable) {
            return snapshot;
        }
        return MultiPlanResponse.MetricSnapshot.builder()
            .label(snapshot.getLabel())
            .sql(snapshot.getSql())
            .explain(snapshot.getExplain())
            .executionTimeMs(snapshot.getExecutionTimeMs())
            .rows(null)
            .scanType(snapshot.getScanType())
            .keyName(snapshot.getKeyName())
            .extra(snapshot.getExtra())
            .build();
    }

    private MultiPlanResponse.ComparisonInfo buildComparison(
        MultiPlanResponse.MetricSnapshot baselineMetrics,
        MultiPlanResponse.MetricSnapshot bestPlanMetrics
    ) {
        Long timeDiff = null;
        Double timePct = null;
        if (baselineMetrics != null && bestPlanMetrics != null
            && baselineMetrics.getExecutionTimeMs() != null
            && bestPlanMetrics.getExecutionTimeMs() != null) {
            timeDiff = baselineMetrics.getExecutionTimeMs() - bestPlanMetrics.getExecutionTimeMs();
            if (baselineMetrics.getExecutionTimeMs() > 0) {
                timePct = timeDiff * 100.0 / baselineMetrics.getExecutionTimeMs();
            }
        }

        Long rowsDiff = null;
        Double rowsPct = null;
        if (baselineMetrics != null && bestPlanMetrics != null
            && baselineMetrics.getRows() != null
            && bestPlanMetrics.getRows() != null) {
            rowsDiff = baselineMetrics.getRows() - bestPlanMetrics.getRows();
            if (baselineMetrics.getRows() > 0) {
                rowsPct = rowsDiff * 100.0 / baselineMetrics.getRows();
            }
        }

        return MultiPlanResponse.ComparisonInfo.builder()
            .executionTimeDiffMs(timeDiff)
            .executionTimeImprovementPct(timePct)
            .rowsDiff(rowsDiff)
            .rowsImprovementPct(rowsPct)
            .build();
    }

    private List<MultiPlanResponse.EvidenceItem> buildEvidence(
        List<OptimizationPlan> plans,
        MultiPlanResponse.MetricSnapshot baselineMetrics,
        MultiPlanResponse.MetricSnapshot bestPlanMetrics
    ) {
        List<MultiPlanResponse.EvidenceItem> evidence = new ArrayList<>();
        evidence.add(MultiPlanResponse.EvidenceItem.builder()
            .category("baseline")
            .sourceType("system")
            .title("优化前基线")
            .summary(buildEvidenceSummary(baselineMetrics))
            .build());

        evidence.add(MultiPlanResponse.EvidenceItem.builder()
            .category("best_plan")
            .sourceType("system")
            .title("最优方案结果")
            .summary(buildEvidenceSummary(bestPlanMetrics))
            .build());

        evidence.add(MultiPlanResponse.EvidenceItem.builder()
            .category("plans")
            .sourceType("agent")
            .title("候选方案")
            .summary("共生成 " + plans.size() + " 个候选方案，用于策略对比")
            .build());
        return evidence;
    }

    private String buildEvidenceSummary(MultiPlanResponse.MetricSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return String.format(
            "executionTime=%sms, rows=%s, type=%s, key=%s",
            snapshot.getExecutionTimeMs() != null ? snapshot.getExecutionTimeMs() : "-",
            snapshot.getRows() != null ? snapshot.getRows() : "-",
            snapshot.getScanType() != null ? snapshot.getScanType() : "-",
            snapshot.getKeyName() != null ? snapshot.getKeyName() : "-"
        );
    }

    private String safeValue(Object value) {
        return value != null ? String.valueOf(value) : "-";
    }

    private String formatStrategyName(String type) {
        if (type == null) {
            return "SQL 优化";
        }
        return type.toLowerCase()
            .replace("sql_rewrite", "SQL 改写")
            .replace("index_optimization", "索引优化")
            .replace("between_optimization", "范围查询优化")
            .replace("covering_index", "覆盖索引")
            .replace("mixed_strategy", "混合优化")
            .replace("_", " ");
    }

    private OptimizationPlan buildOptimizationPlan(PlanCandidate candidate, PlanExecutionResult result, String bestPlanId) {
        OptimizationPlan.OptimizationPlanBuilder builder = OptimizationPlan.builder()
            .planId(candidate.getPlanId())
            .type(candidate.getType())
            .optimizedSql(candidate.getOptimizedSql())
            .originalSql(candidate.requiresRewrite() ? null : candidate.getOptimizedSql())
            .indexDDL(candidate.getIndexDDL())
            .description(candidate.getDescription())
            .reasoning(candidate.getReasoning())
            .priority(candidate.getPriority())
            .estimatedImpact(candidate.getEstimatedImpact())
            .riskLevel(candidate.getRiskLevel());

        if (result == null) {
            builder.valid(false).validationError("执行结果缺失");
        } else {
            builder.explain(result.getExplain())
                .executionTime(result.getExecutionTime())
                .resultEqual(Boolean.TRUE.equals(result.getResultEqual()))
                .valid(result.isValid())
                .validationError(result.getValidationError());
        }

        if (bestPlanId != null && !bestPlanId.equals(candidate.getPlanId())) {
            builder.rejectionReason(generateRejectionReason(candidate, result));
        }

        return builder.build();
    }

    private String generateRejectionReason(PlanCandidate candidate, PlanExecutionResult result) {
        if (result == null || !result.isValid()) {
            return "方案验证失败或结果不正确";
        }
        if (candidate.requiresRewrite() && !candidate.requiresIndex()) {
            return "该方案只改写 SQL，完整性不如包含索引优化的方案";
        }
        if (candidate.getPriority() != null && candidate.getPriority() > 1) {
            return "优先级较低，存在更优候选方案";
        }
        return "综合评分低于最优方案";
    }

    private PlanExecutionResult findResult(List<PlanExecutionResult> results, String planId) {
        return results.stream()
            .filter(r -> planId.equals(r.getPlanId()))
            .findFirst()
            .orElse(null);
    }

    public AgentExecutionStep buildSelectionStep(EvaluationReport report) {
        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("selection");
        step.setStepName("selectBestPlan");
        step.setSourceType("inferred");
        step.setDataSource("system");
        step.setStatus(report.getBestPlanId() != null ? "SUCCESS" : "FAILED");
        step.setToolName(report.getBestPlanId());
        step.setDescription("系统完成候选方案评分与最优方案选择");
        step.setInputSummary("scores=" + (report.getScores() != null ? report.getScores().size() : 0));
        step.setOutputSummary("bestPlanId=" + safeValue(report.getBestPlanId()));
        step.setDetails(report.getReason());
        step.setExecutionTimeMs(0L);
        long now = System.currentTimeMillis();
        step.setStartTime(now);
        step.setEndTime(now);
        step.setTimestamp(now);
        return step;
    }

    public MultiPlanResponse buildEmptyResponse(String message) {
        return MultiPlanResponse.builder()
            .success(false)
            .error(message)
            .build();
    }

    /**
     * 构建快速路径响应（简单主键查询，无需优化）
     */
    public MultiPlanResponse buildFastPathResponse(
        MultiPlanRequest request,
        PlanExecutionResult baselineResult,
        String optimizedSql,
        String sessionId,
        long totalTime
    ) {
        ExplainResult baselineExplain = baselineResult != null ? baselineResult.getExplain() : null;
        SqlPatternAdvisor.Analysis analysis = sqlPatternAdvisor.analyze(request.getSql(), null);

        // 构建基线指标
        MultiPlanResponse.MetricSnapshot baselineMetrics = buildMetricSnapshot(
            "优化前",
            request.getSql(),
            baselineResult
        );

        // 构建摘要
        String summaryReason = String.format(
            "当前 SQL 已命中索引 %s，访问类型 %s，扫描行数 %d 行",
            baselineExplain != null ? baselineExplain.getKey() : "N/A",
            baselineExplain != null ? baselineExplain.getType() : "N/A",
            baselineExplain != null && baselineExplain.getRows() != null ? baselineExplain.getRows() : 0
        );

        MultiPlanResponse.SummaryInfo summary = MultiPlanResponse.SummaryInfo.builder()
            .improvement("无需优化")
            .strategy("快速路径：简单主键查询")
            .baselineExecutionTime(baselineResult != null ? baselineResult.getExecutionTime() : null)
            .optimizedExecutionTime(baselineResult != null ? baselineResult.getExecutionTime() : null)
            .baselineRows(baselineExplain != null ? baselineExplain.getRows() : null)
            .optimizedRows(baselineExplain != null ? baselineExplain.getRows() : null)
            .selectedPlanSummary("SQL 已使用最优索引，无需进一步优化")
            .build();

        // 构建分析信息
        MultiPlanResponse.AnalysisInfo analysisInfo = MultiPlanResponse.AnalysisInfo.builder()
            .bestPlanReasoning(summaryReason)
            .riskSummary("无风险")
            .tuningNotes(analysis.isHasSelectStar() ? "已将 SELECT * 展开为显式字段" : "SQL 结构良好")
            .plans(new java.util.ArrayList<>())
            .build();

        // 构建核心指标
        MultiPlanResponse.ValueComparison executionTimeComparison = MultiPlanResponse.ValueComparison.builder()
            .baseline(baselineResult != null && baselineResult.getExecutionTime() != null ? baselineResult.getExecutionTime().doubleValue() : null)
            .optimized(baselineResult != null && baselineResult.getExecutionTime() != null ? baselineResult.getExecutionTime().doubleValue() : null)
            .delta(0.0)
            .deltaPercentage(0.0)
            .unit("ms")
            .build();

        MultiPlanResponse.ValueComparison rowsComparison = MultiPlanResponse.ValueComparison.builder()
            .baseline(baselineExplain != null && baselineExplain.getRows() != null ? baselineExplain.getRows().doubleValue() : null)
            .optimized(baselineExplain != null && baselineExplain.getRows() != null ? baselineExplain.getRows().doubleValue() : null)
            .delta(0.0)
            .deltaPercentage(0.0)
            .unit("rows")
            .build();

        MultiPlanResponse.CoreMetrics coreMetrics = MultiPlanResponse.CoreMetrics.builder()
            .executionTimeMs(executionTimeComparison)
            .scannedRows(rowsComparison)
            .improvementPercentage(null)
            .build();

        // 构建执行计划对比
        MultiPlanResponse.ExplainComparison explainComparison = MultiPlanResponse.ExplainComparison.builder()
            .baseline(baselineExplain)
            .optimized(null)
            .candidates(new java.util.ArrayList<>())
            .diffSummary(new java.util.ArrayList<>())
            .build();

        // 构建方案选择摘要
        MultiPlanResponse.PlanSelectionSummary planSelection = MultiPlanResponse.PlanSelectionSummary.builder()
            .selectedPlanId(null)
            .selectedReason(summaryReason)
            .candidateCount(0)
            .validCandidateCount(0)
            .build();

        // 构建风险评估
        MultiPlanResponse.RiskAssessment riskAssessment = MultiPlanResponse.RiskAssessment.builder()
            .level("无风险")
            .expectedBenefit("无需优化")
            .strategyNotes("SQL 已使用最优索引")
            .cautions(new java.util.ArrayList<>())
            .build();

        // 构建分析步骤
        List<MultiPlanResponse.AnalysisStep> analysisSteps = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        analysisSteps.add(MultiPlanResponse.AnalysisStep.builder()
            .id("fast-path-1")
            .name("快速路径检查")
            .stepType("fast_path")
            .sourceType("system")
            .status("SUCCESS")
            .inputSummary("type=" + (baselineExplain != null ? baselineExplain.getType() : "N/A"))
            .outputSummary("检测到简单主键查询，跳过 AI 分析")
            .startedAt(now)
            .endedAt(now + totalTime)
            .durationMs(totalTime)
            .build());

        // 构建响应
        return MultiPlanResponse.builder()
            .success(true)
            .originalSql(request.getSql())
            .baselineSql(request.getSql())
            .optimizedSql(optimizedSql)
            .plans(new java.util.ArrayList<>())
            .bestPlanId(null)
            .reason(summaryReason + (analysis.isHasSelectStar() ? "。已将 SELECT * 展开为显式字段" : ""))
            .summary(summary)
            .analysis(analysisInfo)
            .execution(new java.util.ArrayList<>())
            .evidence(new java.util.ArrayList<>())
            .baselineExplain(baselineExplain)
            .baselineMetrics(baselineMetrics)
            .bestPlanMetrics(baselineMetrics) // 优化后与优化前相同
            .comparison(null)
            .coreMetrics(coreMetrics)
            .explainComparison(explainComparison)
            .planSelection(planSelection)
            .riskAssessment(riskAssessment)
            .analysisTimeline(analysisSteps)
            .totalTime(totalTime)
            .baselineExecutionTime(baselineResult != null ? baselineResult.getExecutionTime() : null)
            .bestPlanExecutionTime(baselineResult != null ? baselineResult.getExecutionTime() : null)
            .improvementPercentage(0.0)
            .outcomeStatus("NO_NEED")
            .sessionId(sessionId)
            .modelName(request.getModel())
            .dbName(null)
            .schemaVersion("result-v2")
            .sqlRewriteAdvisory(MultiPlanResponse.SqlRewriteAdvisory.builder()
                .hasSelectStar(analysis.isHasSelectStar())
                .hasDateFunctionPredicate(analysis.isHasDateFunctionPredicate())
                .hasImplicitTypeConversion(analysis.isHasImplicitTypeConversion())
                .autoApplied(analysis.isHasSelectStar() && !optimizedSql.equals(request.getSql()))
                .reason(analysis.isHasSelectStar() ? "已自动展开 SELECT *" : "未检测到显著的 SQL 改写提示")
                .build())
            .build();
    }

    public MultiPlanResponse buildErrorResponse(String error) {
        return MultiPlanResponse.builder()
            .success(false)
            .error(error)
            .build();
    }


}
