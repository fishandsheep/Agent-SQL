package com.sqlagent.evaluator;

import com.sqlagent.model.EvaluationReport;
import com.sqlagent.model.PlanCandidate;
import com.sqlagent.model.PlanExecutionResult;
import com.sqlagent.model.PlanScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 方案评估器
 *
 * 核心职责：
 * - 评分每个方案
 * - 选择最优方案
 * - 生成评估报告
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanEvaluator {

    /**
     * 评估所有方案
     *
     * @param candidates 方案候选列表
     * @param results 执行结果列表
     * @return 评估报告
     */
    public EvaluationReport evaluate(
        List<PlanCandidate> candidates,
        List<PlanExecutionResult> results) {

        log.info("[plan-evaluator] evaluating candidates: count={}", candidates.size());

        // 1. 找到baseline
        PlanExecutionResult baseline = results.stream()
            .filter(r -> "BASELINE".equals(r.getPlanId()))
            .findFirst()
            .orElse(null);

        if (baseline == null) {
            log.warn("[plan-evaluator] baseline result not found");
        }

        // 2. 评分每个方案（基础分）
        List<PlanScore> scores = candidates.stream()
            .map(candidate -> {
                PlanExecutionResult result = findResult(results, candidate.getPlanId());
                if (result == null) {
                    log.warn("[plan-evaluator] execution result missing: planId={}", candidate.getPlanId());
                    return null;
                }
                return PlanScore.calculate(result, candidate, baseline);
            })
            .filter(score -> score != null)
            .collect(Collectors.toList());

        Map<String, PlanScore> scoreMap = scores.stream()
            .collect(Collectors.toMap(PlanScore::getPlanId, score -> score, (left, right) -> left));

        // 3. 计算加分项（新颖性和组合效果）
        for (PlanCandidate candidate : candidates) {
            PlanScore score = scoreMap.get(candidate.getPlanId());
            if (score == null) {
                continue;
            }

            // 3.1 方案新颖性加分
            double noveltyBonus = calculateNoveltyBonus(candidate, candidates);
            score.setNoveltyBonus(noveltyBonus);

            // 3.2 方案组合效果加分
            double combinationBonus = calculateCombinationBonus(candidate);
            score.setCombinationBonus(combinationBonus);
        }

        // 4. 选择最优方案
        List<PlanScore> validScores = scores.stream()
            .filter(score -> isValidResult(results, score.getPlanId()))
            .toList();

        PlanScore bestScore = (validScores.isEmpty() ? scores : validScores).stream()
            .max(Comparator.comparingDouble(PlanScore::getTotalScore))
            .orElse(null);

        // 5. 生成报告
        EvaluationReport.EvaluationReportBuilder builder = EvaluationReport.builder()
            .scores(scores);

        if (bestScore != null) {
            builder.bestPlanId(bestScore.getPlanId());

            // 找到最优候选方案
            PlanCandidate bestCandidate = candidates.stream()
                .filter(c -> bestScore.getPlanId().equals(c.getPlanId()))
                .findFirst()
                .orElse(null);

            // 生成选择理由
            String reason = generateReason(bestScore, bestCandidate, baseline);
            builder.reason(reason);

            // 计算性能提升
            if (baseline != null && baseline.getExecutionTime() != null) {
                builder.baselineExecutionTime(baseline.getExecutionTime());

                PlanExecutionResult bestResult = findResult(results, bestScore.getPlanId());
                if (bestResult != null && bestResult.getExecutionTime() != null) {
                    builder.bestPlanExecutionTime(bestResult.getExecutionTime());

                    double improvement = ((double)(baseline.getExecutionTime() - bestResult.getExecutionTime()))
                        / baseline.getExecutionTime() * 100;
                    builder.improvementPercentage(improvement);
                }
            }
        }

        EvaluationReport report = builder.build();
        log.info("[plan-evaluator] evaluation completed: bestPlanId={}, totalScore={}",
            report.getBestPlanId(),
            bestScore != null ? bestScore.getTotalScore() : 0
        );

        return report;
    }

    /**
     * 计算方案新颖性加分
     *
     * @param candidate 当前方案
     * @param allCandidates 所有方案列表
     * @return 新颖性加分（0-5分）
     */
    private double calculateNoveltyBonus(PlanCandidate candidate, List<PlanCandidate> allCandidates) {
        // 1. 如果方案有优先级且为1，给予新颖性加分
        if (candidate.getPriority() != null
            && candidate.getPriority() == 1
            && candidate.getType() != null
            && candidate.getType().toLowerCase().contains("creative")) {
            return 3.0;
        }

        // 2. 如果方案的type包含创新关键词，给予加分
        if (candidate.getType() != null) {
            String type = candidate.getType().toLowerCase();
            if (type.contains("novel") || type.contains("innovative") || type.contains("creative")) {
                return 3.0;
            }
        }

        // 3. 如果方案是混合策略，给予加分
        if (candidate.requiresRewrite() && candidate.requiresIndex()) {
            long mixedCount = allCandidates.stream()
                .filter(plan -> plan != null && plan.requiresRewrite() && plan.requiresIndex())
                .count();
            return mixedCount <= 1 ? 0.5 : 0.25;
        }

        return 0.0;
    }

    /**
     * 计算方案组合效果加分
     *
     * @param candidate 当前方案
     * @return 组合效果加分（0-5分）
     */
    private double calculateCombinationBonus(PlanCandidate candidate) {
        int strategyCount = 0;

        // 1. 如果有SQL改写，计数+1
        if (candidate.requiresRewrite()) {
            strategyCount++;
        }

        // 2. 如果有索引优化，计数+1
        if (candidate.requiresIndex()) {
            strategyCount++;
        }

        // 3. 根据策略数量给予加分
        // 单策略：0分
        // 双策略：5分（最佳组合）
        if (strategyCount == 2) {
            // 双策略组合：根据索引数量调整加分
            int indexCount = countIndexes(candidate.getIndexDDL());
            if (indexCount <= 1) {
                return 1.0;  // 双策略但索引少，给予基础加分
            }
            if (indexCount == 2) {
                return 1.0;  // 双策略+2个索引，给予标准加分
            }
            return 0.5;  // 双策略+3+个索引，组合加分稍降（考虑写入成本）
        } else if (strategyCount == 1 && candidate.getEstimatedImpact() != null) {
            // 单策略但预期影响大，给予部分加分
            if (!candidate.getEstimatedImpact().isBlank()
                && (candidate.getEstimatedImpact().contains("高") || candidate.getEstimatedImpact().contains("显著"))) {
                return 2.0;  // 单策略但高影响，给予高分
            }
        }

        return 0.0;
    }

    /**
     * 查找方案执行结果
     */
    private int countIndexes(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        String upper = ddl.toUpperCase();
        while ((index = upper.indexOf("CREATE INDEX", index)) != -1) {
            count++;
            index += "CREATE INDEX".length();
        }
        return count;
    }

    private PlanExecutionResult findResult(List<PlanExecutionResult> results, String planId) {
        return results.stream()
            .filter(r -> planId.equals(r.getPlanId()))
            .findFirst()
            .orElse(null);
    }

    private boolean isValidResult(List<PlanExecutionResult> results, String planId) {
        PlanExecutionResult result = findResult(results, planId);
        return result != null && result.isValid();
    }

    /**
     * 生成选择理由
     */
    private String generateReason(PlanScore bestScore, PlanCandidate bestCandidate, PlanExecutionResult baseline) {
        StringBuilder reason = new StringBuilder();

        // 如果有候选方案信息，生成更友好的理由
        if (bestCandidate != null && bestCandidate.getType() != null) {
            String strategy = formatStrategyType(bestCandidate.getType());
            reason.append(String.format("方案%s最优（%s），", bestScore.getPlanId(), strategy));

            if (bestScore.getPerformanceScore() > 30) {
                reason.append("性能显著提升");
            } else if (bestScore.getPerformanceScore() > 15) {
                reason.append("性能有所提升");
            } else {
                reason.append("性能稳定");
            }

            if (bestScore.getNoveltyBonus() > 0) {
                reason.append("，创新优化策略");
            }

            if (bestScore.getCombinationBonus() > 0) {
                reason.append("，组合优化效果良好");
            }

            reason.append(String.format("。总分%.1f分", bestScore.getTotalScore()));

        } else {
            // 原有的理由生成逻辑（向后兼容）
            reason.append(String.format("方案%s最优，总分%.1f分。", bestScore.getPlanId(), bestScore.getTotalScore()));
            reason.append(String.format("性能得分%.1f分", bestScore.getPerformanceScore()));

            if (baseline != null && baseline.getExecutionTime() != null) {
                reason.append("（基于执行时间）");
            }

            reason.append(String.format("，执行计划得分%.1f分", bestScore.getExplainScore()));
            reason.append(String.format("，正确性得分%.1f分", bestScore.getCorrectnessScore()));
            reason.append(String.format("，复杂度得分%.1f分", bestScore.getComplexityScore()));
        }

        return reason.toString();
    }

    /**
     * 格式化策略类型
     */
    private String formatStrategyType(String type) {
        if (type == null) {
            return "SQL优化";
        }

        return type.toLowerCase()
            .replace("sql_rewrite", "SQL改写")
            .replace("index_optimization", "索引优化")
            .replace("between_optimization", "范围查询优化")
            .replace("covering_index", "覆盖索引")
            .replace("mixed_strategy", "混合优化")
            .replace("_", " ");
    }
}
