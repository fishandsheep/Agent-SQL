package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个索引推荐 DTO
 *
 * 表示一个推荐索引的详细信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexRecommendation {

    /**
     * 索引名称
     */
    private String indexName;

    /**
     * 索引列（按顺序）
     */
    private List<String> columns;

    /**
     * 是否唯一索引
     */
    private boolean unique;

    /**
     * 索引类型：BTREE/HASH/FULLTEXT
     */
    private String indexType;

    /**
     * 优先级评分（0-100，越高越推荐）
     */
    private int priorityScore;

    /**
     * 推荐理由
     */
    private String reason;

    /**
     * 创建SQL语句
     */
    private String createSql;

    /**
     * 预估收益
     */
    private double estimatedBenefit;

    /**
     * 表名（Workload优化时使用）
     */
    private String tableName;

    /**
     * 被覆盖的SQL索引列表（Workload优化时使用）
     * 表示这个索引可以优化哪些SQL（SQL在inputSqls中的索引位置）
     */
    private List<Integer> coveredSqls;

    /**
     * 索引类型常量
     */
    public static final String INDEX_BTREE = "BTREE";
    public static final String INDEX_HASH = "HASH";
    public static final String INDEX_FULLTEXT = "FULLTEXT";
}
