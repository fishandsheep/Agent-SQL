package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表列表查询结果
 *
 * 用于返回数据库中所有表的基本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableListResult {
    /**
     * 查询是否成功
     */
    private boolean success;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 错误信息（查询失败时）
     */
    private String error;

    /**
     * 表列表（查询成功时）
     */
    private List<TableInfo> tables;

    /**
     * 表基本信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableInfo {
        /**
         * 表名
         */
        private String tableName;

        /**
         * 存储引擎（如 InnoDB, MyISAM）
         */
        private String engine;

        /**
         * 表行数（估算值）
         */
        private Long rowCount;

        /**
         * 数据大小（格式化字符串，如 "128 KB", "16 MB"）
         */
        private String dataSize;

        /**
         * 索引数量
         */
        private Integer indexCount;
    }
}
