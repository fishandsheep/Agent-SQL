package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表详情查询结果
 *
 * 用于返回指定表的详细结构和索引信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableDetailResult {
    /**
     * 查询是否成功
     */
    private boolean success;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 错误信息（查询失败时）
     */
    private String error;

    /**
     * 列信息列表（查询成功时）
     */
    private List<ColumnInfo> columns;

    /**
     * 索引信息列表（查询成功时）
     */
    private List<IndexResult.IndexInfo> indexes;

    /**
     * 列详细信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnInfo {
        /**
         * 列名
         */
        private String columnName;

        /**
         * 数据类型（如 int, varchar, datetime）
         */
        private String dataType;

        /**
         * 完整列类型（如 varchar(255), int(11)）
         */
        private String columnType;

        /**
         * 是否允许NULL
         */
        private boolean nullable;

        /**
         * 默认值
         */
        private String defaultValue;

        /**
         * 是否为主键
         */
        private boolean primaryKey;

        /**
         * 是否为唯一键
         */
        private boolean uniqueKey;
    }
}
