package com.sqlagent.evaluator;

import com.sqlagent.model.PlanCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案多样性检查器
 *
 * 核心职责：
 * - 检查生成的方案是否过于相似
 * - 计算方案间的相似度分数
 * - 如果相似度过高，建议AI生成不同方向的方案
 */
@Slf4j
@Component
public class PlanDiversityChecker {

    /**
     * 相似度阈值（0.0-1.0）
     * 如果两个方案的相似度超过这个阈值，则认为它们过于相似
     */
    private static final double SIMILARITY_THRESHOLD = 0.7;

    /**
     * 检查方案列表是否具有足够的多样性
     *
     * @param candidates 方案候选列表
     * @return true表示多样性足够，false表示方案过于相似
     */
    public boolean isDiverse(List<PlanCandidate> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return true; // 单个方案总是多样的
        }

        // 检查所有方案对
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                double similarity = calculateSimilarity(candidates.get(i), candidates.get(j));
                if (similarity > SIMILARITY_THRESHOLD) {
                    log.warn("[diversity-checker] plans too similar: leftPlanId={}, rightPlanId={}, similarity={}",
                            candidates.get(i).getPlanId(),
                            candidates.get(j).getPlanId(),
                            similarity);
                    return false;
                }
            }
        }

        log.info("[diversity-checker] diversity check passed");
        return true;
    }

    /**
     * 计算两个方案之间的相似度
     *
     * @param plan1 方案1
     * @param plan2 方案2
     * @return 相似度分数（0.0-1.0）
     */
    public double calculateSimilarity(PlanCandidate plan1, PlanCandidate plan2) {
        double score = 0.0;
        int totalWeight = 0;

        // 1. 类型相似度（权重：2）
        score += calculateTypeSimilarity(plan1.getType(), plan2.getType()) * 2;
        totalWeight += 2;

        // 2. SQL相似度（权重：3）
        if (plan1.requiresRewrite() && plan2.requiresRewrite()) {
            score += calculateSqlSimilarity(plan1.getOptimizedSql(), plan2.getOptimizedSql()) * 3;
            totalWeight += 3;
        } else if (!plan1.requiresRewrite() && !plan2.requiresRewrite()) {
            score += 1.0 * 3; // 都不改写SQL，完全相似
            totalWeight += 3;
        } else {
            score += 0.0 * 3; // 一个改写一个不改写，完全不相似
            totalWeight += 3;
        }

        // 3. 索引相似度（权重：3）
        if (plan1.requiresIndex() && plan2.requiresIndex()) {
            score += calculateIndexSimilarity(plan1.getIndexDDL(), plan2.getIndexDDL()) * 3;
            totalWeight += 3;
        } else if (!plan1.requiresIndex() && !plan2.requiresIndex()) {
            score += 1.0 * 3; // 都不创建索引，完全相似
            totalWeight += 3;
        } else {
            score += 0.0 * 3; // 一个创建一个不创建，完全不相似
            totalWeight += 3;
        }

        // 4. 优化策略相似度（权重：2）
        score += calculateStrategySimilarity(plan1, plan2) * 2;
        totalWeight += 2;

        return score / totalWeight;
    }

    /**
     * 计算类型相似度
     */
    private double calculateTypeSimilarity(String type1, String type2) {
        if (type1 == null || type2 == null) {
            return 0.0;
        }

        // 完全相同
        if (type1.equalsIgnoreCase(type2)) {
            return 1.0;
        }

        // 包含相同关键词
        Set<String> keywords1 = extractKeywords(type1);
        Set<String> keywords2 = extractKeywords(type2);

        Set<String> intersection = new HashSet<>(keywords1);
        intersection.retainAll(keywords2);

        Set<String> union = new HashSet<>(keywords1);
        union.addAll(keywords2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * 计算SQL相似度
     */
    private double calculateSqlSimilarity(String sql1, String sql2) {
        if (sql1 == null || sql2 == null) {
            return 0.0;
        }

        // 标准化SQL
        String normalized1 = normalizeSql(sql1);
        String normalized2 = normalizeSql(sql2);

        // 完全相同
        if (normalized1.equals(normalized2)) {
            return 1.0;
        }

        // 计算词级别的相似度
        // 使用HashSet而不是Set.of，因为可能有重复元素
        Set<String> words1 = new HashSet<>(Arrays.asList(normalized1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(normalized2.split("\\s+")));

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * 计算索引相似度
     */
    private double calculateIndexSimilarity(String indexDDL1, String indexDDL2) {
        if (indexDDL1 == null || indexDDL2 == null) {
            return 0.0;
        }

        // 提取索引列
        Set<String> columns1 = extractIndexColumns(indexDDL1);
        Set<String> columns2 = extractIndexColumns(indexDDL2);

        // 完全相同
        if (columns1.equals(columns2)) {
            return 1.0;
        }

        // 计算列的重叠度
        Set<String> intersection = new HashSet<>(columns1);
        intersection.retainAll(columns2);

        Set<String> union = new HashSet<>(columns1);
        union.addAll(columns2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * 计算优化策略相似度
     */
    private double calculateStrategySimilarity(PlanCandidate plan1, PlanCandidate plan2) {
        boolean bothRewrite = plan1.requiresRewrite() && plan2.requiresRewrite();
        boolean bothIndex = plan1.requiresIndex() && plan2.requiresIndex();
        boolean neitherRewrite = !plan1.requiresRewrite() && !plan2.requiresRewrite();
        boolean neitherIndex = !plan1.requiresIndex() && !plan2.requiresIndex();

        // 策略完全相同
        if ((bothRewrite && bothIndex) || (neitherRewrite && neitherIndex)) {
            return 1.0;
        }

        // 策略部分相同
        if (bothRewrite || bothIndex || neitherRewrite || neitherIndex) {
            return 0.5;
        }

        // 策略完全不同
        return 0.0;
    }

    /**
     * 提取关键词
     */
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        // 分割并转为小写
        String[] parts = text.toLowerCase().split("[_\\-\\s]+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                keywords.add(part);
            }
        }
        return keywords;
    }

    /**
     * 标准化SQL
     */
    private String normalizeSql(String sql) {
        return sql.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .trim();
    }

    /**
     * 提取索引列
     */
    private Set<String> extractIndexColumns(String indexDDL) {
        Set<String> columns = new HashSet<>();

        try {
            // 提取括号内的内容
            int start = indexDDL.indexOf('(');
            int end = indexDDL.indexOf(')');

            if (start >= 0 && end > start) {
                String columnPart = indexDDL.substring(start + 1, end);
                String[] columnArray = columnPart.split(",");

                for (String col : columnArray) {
                    col = col.trim().toLowerCase();
                    if (!col.isEmpty()) {
                        columns.add(col);
                    }
                }
            }
        } catch (Exception e) {
            log.error("提取索引列失败: {}", indexDDL, e);
        }

        return columns;
    }
}