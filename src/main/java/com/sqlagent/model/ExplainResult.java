package com.sqlagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行计划分析结果 DTO
 *
 * ExplainTool 的返回类型
 * 包含 MySQL EXPLAIN 的所有字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)  // 忽略未知字段，避免反序列化失败
public class ExplainResult {

    /**
     * SELECT 标识符
     */
    private Long id;

    /**
     * SELECT 类型：SIMPLE/PRIMARY/UNION/SUBQUERY 等
     */
    private String selectType;

    /**
     * 表名
     */
    private String table;

    /**
     * 分区信息
     */
    private String partitions;

    /**
     * 扫描类型：ALL(全表扫描)/range(范围扫描)/ref(索引查找)/eq_ref(唯一索引)
     */
    private String type;

    /**
     * 可能使用的索引
     */
    private String possibleKeys;

    /**
     * 实际使用的索引名称
     */
    private String key;

    /**
     * 使用的索引长度
     */
    private String keyLen;

    /**
     * 索引比较的列
     */
    private String ref;

    /**
     * 预估扫描行数
     */
    private Long rows;

    /**
     * 过滤的行数百分比
     */
    private Double filtered;

    /**
     * 额外信息：Using where/Using filesort/Using temporary 等
     */
    private String extra;

    /**
     * 执行计划评分（0-100，越高越好）
     */
    private int score;

    /**
     * 是否存在性能问题
     */
    private boolean hasProblem;

    /**
     * 多表查询的额外执行计划行（JSON 数组字符串）
     * 如果是多表查询，此字段包含除第一行外的其他行
     */
    private String additionalRows;
}
