package com.sqlagent.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引记录
 *
 * 记录一个临时索引的原始信息和实际创建信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexRecord {

    /**
     * 实际创建的临时索引名称
     */
    private String tmpIndexName;

    /**
     * 原始索引名称（不含临时前缀）
     */
    private String originalIndexName;

    /**
     * 实际执行的DDL（带临时前缀）
     */
    private String tmpDDL;

    /**
     * 原始DDL（不带临时前缀）
     */
    private String originalDDL;
}
