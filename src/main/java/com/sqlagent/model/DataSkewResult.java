package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据倾斜分析结果 DTO
 *
 * DataSkewTool 的返回类型
 * 分析字段的数据分布倾斜情况
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSkewResult {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 列名
     */
    private String columnName;

    /**
     * 总行数
     */
    private Long totalRows;

    /**
     * Top值分布列表
     */
    private List<ValueDistribution> topValues;

    /**
     * 倾斜系数（0-1，越高表示倾斜越严重）
     */
    private Double skewFactor;

    /**
     * 倾斜等级：NONE/MODERATE/SEVERE
     */
    private String skewLevel;

    /**
     * 处理建议列表
     */
    private List<String> suggestions;

    /**
     * 错误信息（如有）
     */
    private String error;

    /**
     * 倾斜等级常量
     */
    public static final String SKEW_NONE = "NONE";
    public static final String SKEW_MODERATE = "MODERATE";
    public static final String SKEW_SEVERE = "SEVERE";
}
