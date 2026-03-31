package com.sqlagent.exception;

/**
 * 方案执行异常
 *
 * 方案执行过程中失败时抛出
 */
public class PlanExecutionException extends RuntimeException {

    private final String planId;

    public PlanExecutionException(String planId, String message) {
        super(message);
        this.planId = planId;
    }

    public PlanExecutionException(String planId, String message, Throwable cause) {
        super(message, cause);
        this.planId = planId;
    }

    public String getPlanId() {
        return planId;
    }

    /**
     * 方案验证异常
     */
    public static class PlanValidationException extends PlanExecutionException {
        public PlanValidationException(String planId, String message) {
            super(planId, message);
        }
    }

    /**
     * 索引创建异常
     */
    public static class IndexCreationException extends PlanExecutionException {
        public IndexCreationException(String planId, String message, Throwable cause) {
            super(planId, message, cause);
        }
    }

    /**
     * SQL执行异常
     */
    public static class SqlExecutionException extends PlanExecutionException {
        public SqlExecutionException(String planId, String message, Throwable cause) {
            super(planId, message, cause);
        }
    }
}
