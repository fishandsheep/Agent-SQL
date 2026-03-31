package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DDL查询结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DDLResult {
    /**
     * 表名
     */
    private String tableName;

    /**
     * 表结构信息
     */
    private String tableStructure;

    /**
     * 索引信息
     */
    private String indexes;
}
