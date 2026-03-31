package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SQL执行结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResult {
    /**
     * 执行的SQL
     */
    private String sql;

    /**
     * 返回的行数
     */
    private int rowCount;

    /**
     * 执行时间（毫秒）
     */
    private long executionTimeMs;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 错误信息
     */
    private String error;
}
