package com.sqlagent.enums;

/**
 * 方案执行状态
 */
public enum ExecutionStatus {

    /**
     * 待执行
     */
    PENDING,

    /**
     * 执行中
     */
    EXECUTING,

    /**
     * 执行成功
     */
    SUCCESS,

    /**
     * 执行失败
     */
    FAILED,

    /**
     * 验证错误（结果不一致）
     */
    VALIDATION_ERROR
}
