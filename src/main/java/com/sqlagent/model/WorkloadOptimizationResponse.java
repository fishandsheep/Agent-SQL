package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Workload优化响应 DTO
 *
 * 包含多条SQL的全局索引优化建议
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadOptimizationResponse {

    /**
     * 输入的SQL列表
     */
    @Builder.Default
    private List<String> inputSqls = new ArrayList<>();

    /**
     * 推荐的索引列表
     */
    @Builder.Default
    private List<IndexRecommendation> recommendedIndexes = new ArrayList<>();

    /**
     * 被覆盖的SQL数量
     * 至少有一个推荐索引的SQL数量
     */
    private int coveredSqlCount;

    /**
     * 覆盖率（百分比）
     * coveredSqlCount / inputSqls.size() * 100
     */
    private double coverageRate;

    /**
     * 推荐理由
     * 说明为什么推荐这些索引
     */
    private String reason;

    /**
     * 详细分析数据
     * 包含每个SQL的分析结果、字段共现频率等
     */
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    /**
     * Agent执行步骤（工具调用记录）
     */
    @Builder.Default
    private List<AgentExecutionStep> execution = new ArrayList<>();

    /**
     * 总耗时（毫秒）
     */
    private Long totalTime;

    /**
     * 是否生成成功
     */
    private boolean success;

    /**
     * 错误信息（如果success=false）
     */
    private String errorMessage;

    /**
     * 计算覆盖率
     */
    public void calculateCoverageRate() {
        if (inputSqls != null && !inputSqls.isEmpty()) {
            this.coverageRate = (double) coveredSqlCount / inputSqls.size() * 100.0;
        } else {
            this.coverageRate = 0.0;
        }
    }

    /**
     * 获取索引总数
     */
    public int getTotalIndexCount() {
        return recommendedIndexes != null ? recommendedIndexes.size() : 0;
    }

    /**
     * 获取覆盖的SQL索引列表
     */
    public List<Integer> getCoveredSqlIndexes() {
        List<Integer> coveredIndexes = new ArrayList<>();
        if (recommendedIndexes != null) {
            for (IndexRecommendation index : recommendedIndexes) {
                if (index.getCoveredSqls() != null) {
                    for (Integer sqlIndex : index.getCoveredSqls()) {
                        if (!coveredIndexes.contains(sqlIndex)) {
                            coveredIndexes.add(sqlIndex);
                        }
                    }
                }
            }
        }
        return coveredIndexes;
    }
}
