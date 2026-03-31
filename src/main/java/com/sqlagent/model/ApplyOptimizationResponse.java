package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 应用优化响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyOptimizationResponse {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 创建的索引名称
     */
    private String createdIndexName;

    /**
     * 索引DDL
     */
    private String indexDDL;

    /**
     * 错误信息（如果失败）
     */
    private String error;

    /**
     * 提示信息
     */
    private String message;
}
