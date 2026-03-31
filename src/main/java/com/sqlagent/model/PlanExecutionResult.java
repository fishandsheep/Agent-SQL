package com.sqlagent.model;

import com.sqlagent.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方案执行结果
 *
 * 系统执行方案后的完整验证数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanExecutionResult {

    /**
     * 方案ID
     */
    private String planId;

    /**
     * 执行状态
     */
    private ExecutionStatus status;

    /**
     * 执行计划结果
     */
    private ExplainResult explain;

    /**
     * 执行时间（毫秒）
     */
    private Long executionTime;

    /**
     * 结果一致性验证
     */
    private Boolean resultEqual;

    /**
     * 验证错误信息
     */
    private String validationError;

    /**
     * 实际创建的索引名称（临时索引）
     */
    private String createdIndexName;

    /**
     * 执行错误信息
     */
    private String error;

    /**
     * 是否验证通过
     */
    public boolean isValid() {
        return status == ExecutionStatus.SUCCESS
            && explain != null
            && executionTime != null
            && resultEqual != null
            && resultEqual;
    }
}
