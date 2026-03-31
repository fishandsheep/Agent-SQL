package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 列统计信息 DTO
 *
 * 包含单个列的详细统计信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnStatistics {

    /**
     * 列名
     */
    private String columnName;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 基数（distinct 值数量）
     */
    private Long cardinality;

    /**
     * 选择性（cardinality / totalRows）
     */
    private Double selectivity;

    /**
     * 选择性等级：LOW(<5%), MEDIUM(5-50%), HIGH(>50%)
     */
    private String selectivityLevel;

    /**
     * 空值数量
     */
    private Long nullCount;

    /**
     * 空值比例
     */
    private Double nullRatio;

    /**
     * 平均长度
     */
    private Double avgLength;

    /**
     * 示例值（转换为字符串，避免Jackson序列化LocalDateTime等问题）
     */
    private String sampleValue;

    /**
     * 推荐建议
     */
    private String recommendation;
}
