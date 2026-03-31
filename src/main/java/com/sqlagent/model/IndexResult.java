package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引操作结果
 *
 * 用于返回索引创建、删除、查询操作的详细结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexResult {

    /**
     * 操作是否成功
     */
    private boolean success;

    /**
     * 操作类型：create/drop/list
     */
    private String action;

    /**
     * 生成的SQL语句
     */
    private String sql;

    /**
     * 是否已执行（enableDDL=false时为false）
     */
    private boolean executed;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTimeMs;

    /**
     * 结果说明
     */
    private String message;

    /**
     * 回滚SQL（仅create/drop操作）
     * create: DROP INDEX index_name ON table
     * drop: CREATE INDEX index_name ON table(columns)
     */
    private String rollbackSql;

    /**
     * 警告列表
     * 例如：["单表索引数量已超过8个，可能影响写入性能"]
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 优化建议列表
     * 例如：["考虑将索引idx_user_status改为联合索引(user_id,status)"]
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 索引列表（仅list操作或create/drop时返回当前索引信息）
     */
    private List<IndexInfo> indexes = new ArrayList<>();

    /**
     * 错误信息
     */
    private String error;

    /**
     * 索引信息内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexInfo {
        /**
         * 索引名称
         */
        private String indexName;

        /**
         * 索引列列表（按顺序）
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
         * 是否主键
         */
        private boolean primaryKey;

        /**
         * 首列名称（用于最左前缀判断）
         */
        private String leadingColumn;
    }
}
