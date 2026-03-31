package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具调用日志
 *
 * 记录所有工具调用，用于审计和调试
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLog {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * Agent模式
     */
    /**
     * 调用时间
     */
    private LocalDateTime timestamp;

    /**
     * 是否允许
     */
    private boolean permitted;

    /**
     * 参数（JSON字符串）
     */
    private String arguments;

    /**
     * 执行时长（毫秒）
     */
    private Long duration;

    /**
     * 错误信息（如果被拒绝）
     */
    private String errorMessage;
}
