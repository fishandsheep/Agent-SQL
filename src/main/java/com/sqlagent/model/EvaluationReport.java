package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估报告
 *
 * 包含所有方案的评分和最优方案选择
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReport {

    /**
     * 所有方案的评分
     */
    @Builder.Default
    private List<PlanScore> scores = new ArrayList<>();

    /**
     * 最优方案ID
     */
    private String bestPlanId;

    /**
     * 选择理由
     */
    private String reason;

    /**
     * 基准执行时间（原始SQL）
     */
    private Long baselineExecutionTime;

    /**
     * 最优方案执行时间
     */
    private Long bestPlanExecutionTime;

    /**
     * 性能提升百分比
     */
    private Double improvementPercentage;
}
