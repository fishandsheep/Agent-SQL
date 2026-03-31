package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 优化方案候选
 *
 * 由Agent生成，不包含执行结果
 * 支持动态类型：type字段为自由文本，不再限制为rewrite/index/mixed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanCandidate {

    /**
     * 方案ID（A, B, C）
     */
    private String planId;

    /**
     * 方案类型（自由命名）
     * 示例：sql_rewrite, index_optimization, mixed_strategy等
     * 不再限制为rewrite/index/mixed
     */
    private String type;

    /**
     * 优化后的SQL（可能为空）
     */
    private String optimizedSql;

    /**
     * 索引DDL字符串（可能为空）
     */
    private String indexDDL;

    /**
     * 方案说明
     */
    private String description;

    /**
     * Agent的分析理由
     */
    private String reasoning;

    /**
     * 方案优先级（1最高，2次之，3最低）
     */
    private Integer priority;

    /**
     * 预期影响（可选）
     * 描述预期的性能提升百分比
     */
    private String estimatedImpact;

    /**
     * 风险等级（low, medium, high）
     */
    private String riskLevel;

    /**
     * 是否需要创建索引
     */
    public boolean requiresIndex() {
        return indexDDL != null && !indexDDL.isEmpty();
    }

    /**
     * 是否需要改写SQL
     */
    public boolean requiresRewrite() {
        return optimizedSql != null && !optimizedSql.isEmpty();
    }
}
