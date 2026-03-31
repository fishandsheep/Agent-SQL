package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方案评分
 *
 * 综合评分算法
 * 基础分：
 * - 性能得分（40分）：基于相对baseline的性能提升百分比
 * - 执行计划得分（30分）：explain.score * 0.3
 * - 正确性得分（20分）：resultEqual ? 20 : 0
 * - 复杂度得分（10分）：不创建索引10分，1个索引8分，2个索引5分，3+索引2分
 *
 * 加分项：
 * - 方案新颖性（+5分）：首次出现的优化策略或创新方案
 * - 方案组合效果（+5分）：多优化组合效果良好的方案
 *
 * 总分：0-110分
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanScore {

    /**
     * 方案ID
     */
    private String planId;

    /**
     * 总分（0-110）
     */
    private double totalScore;

    /**
     * 性能得分（0-40）
     */
    private double performanceScore;

    /**
     * 执行计划得分（0-30）
     */
    private double explainScore;

    /**
     * 正确性得分（0-20）
     */
    private double correctnessScore;

    /**
     * 复杂度得分（0-10）
     */
    private double complexityScore;

    /**
     * 方案新颖性加分（0-5）
     */
    private double noveltyBonus;

    /**
     * 方案组合效果加分（0-5）
     */
    private double combinationBonus;

    /**
     * 计算方案评分
     *
     * @param result 执行结果
     * @param candidate 方案候选
     * @param baseline 基准执行结果（原始SQL）
     * @return 评分
     */
    public static PlanScore calculate(
        PlanExecutionResult result,
        PlanCandidate candidate,
        PlanExecutionResult baseline) {

        PlanScore.PlanScoreBuilder builder = PlanScore.builder()
            .planId(result.getPlanId());

        // 1. 性能得分（0-40分）
        double performanceScore = calculatePerformanceScore(result, baseline);
        builder.performanceScore(performanceScore);

        // 2. 执行计划得分（0-30分）
        double explainScore = calculateExplainScore(result, baseline);
        builder.explainScore(explainScore);

        // 3. 正确性得分（0-20分）
        double correctnessScore = calculateCorrectnessScore(result);
        builder.correctnessScore(correctnessScore);

        // 4. 复杂度得分（0-10分）
        double complexityScore = calculateComplexityScore(candidate);
        builder.complexityScore(complexityScore);

        // 5. 方案新颖性加分（0-5分）- 默认为0，由外部设置
        builder.noveltyBonus(0.0);

        // 6. 方案组合效果加分（0-5分）- 默认为0，由外部设置
        builder.combinationBonus(0.0);

        // 7. 总分（基础分 + 加分项）
        double totalScore = performanceScore + explainScore + correctnessScore + complexityScore;
        builder.totalScore(totalScore);

        return builder.build();
    }

    /**
     * 设置方案新颖性加分
     * 用于外部调用，在评估所有方案后设置
     *
     * @param noveltyBonus 新颖性加分（0-5分）
     */
    public void setNoveltyBonus(double noveltyBonus) {
        this.noveltyBonus = Math.min(5.0, Math.max(0.0, noveltyBonus));
        // 更新总分
        this.totalScore = this.performanceScore + this.explainScore +
                          this.correctnessScore + this.complexityScore +
                          this.noveltyBonus + this.combinationBonus;
    }

    /**
     * 设置方案组合效果加分
     * 用于外部调用，在评估所有方案后设置
     *
     * @param combinationBonus 组合效果加分（0-5分）
     */
    public void setCombinationBonus(double combinationBonus) {
        this.combinationBonus = Math.min(5.0, Math.max(0.0, combinationBonus));
        // 更新总分
        this.totalScore = this.performanceScore + this.explainScore +
                          this.correctnessScore + this.complexityScore +
                          this.noveltyBonus + this.combinationBonus;
    }

    /**
     * 计算性能得分
     * 基于相对baseline的性能提升百分比
     */
    private static double calculatePerformanceScore(
        PlanExecutionResult result,
        PlanExecutionResult baseline) {

        if (baseline.getExecutionTime() == null || result.getExecutionTime() == null) {
            return 0.0;
        }

        long baselineTime = baseline.getExecutionTime();
        long planTime = result.getExecutionTime();

        if (planTime >= baselineTime) {
            // 没有性能提升，给基础分
            return 5.0;
        }

        // 性能提升百分比
        double improvement = ((double)(baselineTime - planTime)) / baselineTime;

        // 最多40分，根据提升百分比线性计算
        // 10%提升 = 4分，50%提升 = 20分，100%提升 = 40分
        double score = Math.min(40.0, improvement * 40.0);
        return Math.max(0.0, score);
    }

    /**
     * 计算执行计划得分
     * explain.score * 0.3
     */
    private static double calculateExplainScore(PlanExecutionResult result, PlanExecutionResult baseline) {
        if (result.getExplain() == null) {
            return 0.0;
        }

        if (baseline == null || baseline.getExplain() == null) {
            return result.getExplain().getScore() * 0.2;
        }

        double scoreDelta = Math.max(0, result.getExplain().getScore() - baseline.getExplain().getScore());
        double scorePart = Math.min(20.0, scoreDelta * 0.5);

        double rowPart = 0.0;
        Long baselineRows = baseline.getExplain().getRows();
        Long resultRows = result.getExplain().getRows();
        if (areComparableExplainRows(baseline.getExplain(), result.getExplain())
            && baselineRows != null && resultRows != null
            && baselineRows > 0 && resultRows < baselineRows) {
            rowPart = Math.min(10.0, ((double) (baselineRows - resultRows) / baselineRows) * 10.0);
        }

        return Math.min(30.0, scorePart + rowPart);
    }

    private static boolean areComparableExplainRows(ExplainResult baselineExplain, ExplainResult resultExplain) {
        if (baselineExplain == null || resultExplain == null) {
            return false;
        }
        String baselineTable = normalizeIdentifier(baselineExplain.getTable());
        String resultTable = normalizeIdentifier(resultExplain.getTable());
        String baselineType = normalizeIdentifier(baselineExplain.getSelectType());
        String resultType = normalizeIdentifier(resultExplain.getSelectType());
        return !baselineTable.isEmpty()
            && baselineTable.equals(resultTable)
            && !baselineType.isEmpty()
            && baselineType.equals(resultType);
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().replace("`", "").toLowerCase();
    }

    /**
     * 计算正确性得分
     * resultEqual ? 20 : 0
     */
    private static double calculateCorrectnessScore(PlanExecutionResult result) {
        if (result.getResultEqual() == null || !result.getResultEqual()) {
            return 0.0;
        }
        return 20.0;
    }

    /**
     * 计算复杂度得分
     * 不创建索引10分，1个索引8分，2个索引5分，3+索引2分
     */
    private static double calculateComplexityScore(PlanCandidate candidate) {
        if (!candidate.requiresIndex()) {
            return 10.0;
        }

        // 简单判断索引数量（通过统计DDL中的"INDEX"关键字）
        int indexCount = countIndexes(candidate.getIndexDDL());

        return switch (indexCount) {
            case 1 -> 8.5;
            case 2 -> 6.5;
            default -> 4.0;  // 3+个索引
        };
    }

    /**
     * 统计DDL中的索引数量
     */
    private static int countIndexes(String ddl) {
        if (ddl == null || ddl.isEmpty()) {
            return 0;
        }
        // 简单统计：计算"CREATE INDEX"出现次数
        int count = 0;
        int index = 0;
        String upper = ddl.toUpperCase();
        while ((index = upper.indexOf("CREATE INDEX", index)) != -1) {
            count++;
            index += "CREATE INDEX".length();
        }
        return count;
    }
}
