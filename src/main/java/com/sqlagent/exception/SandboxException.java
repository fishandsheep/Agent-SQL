package com.sqlagent.exception;

/**
 * 索引沙箱异常
 *
 * 索引沙箱操作失败时抛出
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 沙箱创建异常
     */
    public static class SandboxCreationException extends SandboxException {
        public SandboxCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 索引执行异常
     */
    public static class IndexExecutionException extends SandboxException {
        private final String indexDDL;

        public IndexExecutionException(String indexDDL, String message, Throwable cause) {
            super(message, cause);
            this.indexDDL = indexDDL;
        }

        public String getIndexDDL() {
            return indexDDL;
        }
    }

    /**
     * 沙箱清理异常
     */
    public static class SandboxCleanupException extends SandboxException {
        private final int failedCount;

        public SandboxCleanupException(int failedCount, String message, Throwable cause) {
            super(message, cause);
            this.failedCount = failedCount;
        }

        public int getFailedCount() {
            return failedCount;
        }
    }
}
