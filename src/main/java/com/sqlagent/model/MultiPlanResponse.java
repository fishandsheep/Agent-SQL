package com.sqlagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultiPlanResponse {

    private String originalSql;
    private String baselineSql;
    private String optimizedSql;

    @Builder.Default
    private List<OptimizationPlan> plans = new ArrayList<>();

    private String bestPlanId;
    private String reason;
    private SummaryInfo summary;
    private AnalysisInfo analysis;

    @Builder.Default
    private List<AgentExecutionStep> execution = new ArrayList<>();

    @Builder.Default
    private List<EvidenceItem> evidence = new ArrayList<>();

    private ExplainResult baselineExplain;
    private MetricSnapshot baselineMetrics;
    private MetricSnapshot bestPlanMetrics;
    private ComparisonInfo comparison;
    private CoreMetrics coreMetrics;
    private ExplainComparison explainComparison;
    private PlanSelectionSummary planSelection;
    private RiskAssessment riskAssessment;
    @Builder.Default
    private List<AnalysisStep> analysisTimeline = new ArrayList<>();

    private Long totalTime;
    private boolean success;
    private String errorMessage;
    private String error;
    private Long baselineExecutionTime;
    private Long bestPlanExecutionTime;
    private Double improvementPercentage;
    private String outcomeStatus;
    private String sessionId;
    private String modelName;
    private String dbName;
    private String schemaVersion;
    private SqlRewriteAdvisory sqlRewriteAdvisory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummaryInfo {
        private String improvement;
        private String strategy;
        private Long baselineExecutionTime;
        private Long optimizedExecutionTime;
        private Long baselineRows;
        private Long optimizedRows;
        private String selectedPlanSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalysisInfo {
        private String bestPlanReasoning;
        private String riskSummary;
        private String tuningNotes;
        @Builder.Default
        private List<OptimizationPlan> plans = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricSnapshot {
        private String label;
        private String sql;
        private ExplainResult explain;
        private Long executionTimeMs;
        private Long rows;
        private String scanType;
        private String keyName;
        private String extra;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComparisonInfo {
        private Long executionTimeDiffMs;
        private Double executionTimeImprovementPct;
        private Long rowsDiff;
        private Double rowsImprovementPct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CoreMetrics {
        private ValueComparison executionTimeMs;
        private ValueComparison scannedRows;
        private ValueComparison improvementPercentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValueComparison {
        private Double baseline;
        private Double optimized;
        private Double delta;
        private Double deltaPercentage;
        private String unit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExplainComparison {
        private ExplainResult baseline;
        private ExplainResult optimized;
        @Builder.Default
        private List<ExplainResult> candidates = new ArrayList<>();
        @Builder.Default
        private List<String> diffSummary = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanSelectionSummary {
        private String selectedPlanId;
        private String selectedReason;
        private Integer candidateCount;
        private Integer validCandidateCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RiskAssessment {
        private String level;
        private String expectedBenefit;
        private String strategyNotes;
        @Builder.Default
        private List<String> cautions = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalysisStep {
        private String id;
        private String name;
        private String stepType;
        private String sourceType;
        private String status;
        private String inputSummary;
        private String outputSummary;
        private Long startedAt;
        private Long endedAt;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SqlRewriteAdvisory {
        private boolean hasSelectStar;
        private boolean hasDateFunctionPredicate;
        private boolean hasImplicitTypeConversion;
        private boolean autoApplied;
        private String suggestedSql;
        private String reason;
        @Builder.Default
        private List<String> issues = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvidenceItem {
        private String category;
        private String sourceType;
        private String title;
        private String summary;
    }

    @JsonIgnore
    public OptimizationPlan getBestPlan() {
        if (bestPlanId == null || plans == null) {
            return null;
        }
        return plans.stream()
            .filter(plan -> bestPlanId.equals(plan.getPlanId()))
            .findFirst()
            .orElse(null);
    }

    @JsonIgnore
    public List<OptimizationPlan> getValidPlans() {
        if (plans == null) {
            return new ArrayList<>();
        }
        return plans.stream()
            .filter(OptimizationPlan::isValid)
            .toList();
    }

    @JsonIgnore
    public int getTotalPlanCount() {
        return plans != null ? plans.size() : 0;
    }

    @JsonIgnore
    public int getValidPlanCount() {
        return getValidPlans().size();
    }

    public void setError(String error) {
        this.errorMessage = error;
        this.error = error;
    }

    public String getError() {
        return errorMessage != null ? errorMessage : error;
    }

    public String getBaselineSql() {
        return baselineSql != null ? baselineSql : originalSql;
    }

    public String getOptimizedSql() {
        if (optimizedSql != null) {
            return optimizedSql;
        }
        if (bestPlanMetrics != null && bestPlanMetrics.getSql() != null) {
            return bestPlanMetrics.getSql();
        }
        OptimizationPlan bestPlan = getBestPlan();
        return bestPlan != null ? bestPlan.getOptimizedSql() : null;
    }
}
