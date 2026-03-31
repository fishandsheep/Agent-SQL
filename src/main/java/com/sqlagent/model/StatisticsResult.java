package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统计信息分析结果 DTO
 *
 * StatisticsTool 的返回类型
 * 包含表的统计信息和列的详细统计
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsResult {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 总行数
     */
    private Long totalRows;

    /**
     * 列统计信息列表
     */
    private List<ColumnStatistics> columns;

    /**
     * 低选择性列列表（不适合建索引）
     */
    private List<String> lowSelectivityColumns;

    /**
     * 高选择性列列表（适合建索引）
     */
    private List<String> highSelectivityColumns;

    /**
     * 警告信息列表
     */
    private List<String> warnings;

    /**
     * 优化建议列表
     */
    private List<String> suggestions;

    /**
     * 错误信息（如有）
     */
    private String error;
}
