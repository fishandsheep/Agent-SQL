package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 覆盖索引分析结果 DTO
 *
 * CoveringIndexTool 的返回类型
 * 分析SQL是否可以使用覆盖索引优化
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoveringIndexResult {

    /**
     * 原始SQL
     */
    private String originalSql;

    /**
     * 表名
     */
    private String tableName;

    /**
     * SELECT 列表
     */
    private List<String> selectedColumns;

    /**
     * WHERE 条件列
     */
    private List<String> whereColumns;

    /**
     * 现有索引列表
     */
    private List<String> existingIndexes;

    /**
     * 是否已有覆盖索引
     */
    private boolean hasCoveringIndex;

    /**
     * 覆盖索引名称（如果存在）
     */
    private String coveringIndexName;

    /**
     * 推荐索引定义（如需要）
     */
    private String recommendedIndex;

    /**
     * 是否存在回表问题
     */
    private boolean hasTableLookup;

    /**
     * 收益分析
     */
    private String benefitAnalysis;

    /**
     * 错误信息（如有）
     */
    private String error;
}
