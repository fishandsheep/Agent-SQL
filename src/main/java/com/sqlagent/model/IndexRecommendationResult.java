package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 索引推荐结果 DTO
 *
 * IndexRecommendationEngine 的返回类型
 * 包含多个推荐方案
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexRecommendationResult {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 分析的SQL
     */
    private String analyzedSql;

    /**
     * 推荐方案列表
     */
    private List<IndexRecommendation> recommendations;

    /**
     * 最佳推荐方案
     */
    private IndexRecommendation bestRecommendation;

    /**
     * 分析说明
     */
    private String analysis;

    /**
     * 当前执行计划评分
     */
    private int currentScore;

    /**
     * 优化后预估评分
     */
    private int estimatedScore;

    /**
     * 错误信息（如有）
     */
    private String error;
}
