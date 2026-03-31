package com.sqlagent.gateway;

import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.ToolCallLog;
import com.sqlagent.service.ExecutionTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class ToolGateway {

    private final ExecutionTraceService executionTraceService;

    private final Map<String, List<ToolCallLog>> sessionLogs = new ConcurrentHashMap<>();
    private final ThreadLocal<String> activeSessionId = new ThreadLocal<>();

    public ToolGateway(ExecutionTraceService executionTraceService) {
        this.executionTraceService = executionTraceService;
    }

    public void bindSessionAlias(String alias, String sessionId) {
        activateSession(sessionId);
    }

    public void activateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            activeSessionId.remove();
            return;
        }
        activeSessionId.set(sessionId);
    }

    public void clearActiveSession() {
        activeSessionId.remove();
    }

    public void logToolCall(String sessionId, String toolName, Object[] args, long duration) {
        String resolvedSessionId = resolveSessionId(sessionId);
        ToolCallLog logEntry = ToolCallLog.builder()
            .sessionId(resolvedSessionId)
            .toolName(toolName)
            .timestamp(LocalDateTime.now())
            .permitted(true)
            .arguments(formatArguments(args))
            .duration(duration)
            .build();

        sessionLogs.computeIfAbsent(resolvedSessionId, key -> new CopyOnWriteArrayList<>()).add(logEntry);
        executionTraceService.recordStep(resolvedSessionId, buildToolStep(logEntry));
        log.debug("[Gateway] tool call logged: tool={}, duration={}ms", toolName, duration);
    }

    public void logToolCallRejected(String sessionId, String toolName, Object[] args, String errorMessage) {
        String resolvedSessionId = resolveSessionId(sessionId);
        ToolCallLog logEntry = ToolCallLog.builder()
            .sessionId(resolvedSessionId)
            .toolName(toolName)
            .timestamp(LocalDateTime.now())
            .permitted(false)
            .arguments(formatArguments(args))
            .errorMessage(errorMessage)
            .build();

        sessionLogs.computeIfAbsent(resolvedSessionId, key -> new CopyOnWriteArrayList<>()).add(logEntry);
        executionTraceService.recordStep(resolvedSessionId, buildToolStep(logEntry));
    }

    public List<ToolCallLog> getSessionLogs(String sessionId) {
        return new ArrayList<>(sessionLogs.getOrDefault(resolveSessionId(sessionId), List.of()));
    }

    public void clearSessionLogs(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        sessionLogs.remove(resolvedSessionId);
        log.info("[Gateway] cleared session logs: {}", resolvedSessionId);
    }

    public void clearSession(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        clearSessionLogs(resolvedSessionId);
        String currentSessionId = activeSessionId.get();
        if (resolvedSessionId != null && resolvedSessionId.equals(currentSessionId)) {
            clearActiveSession();
        }
    }

    public List<String> getActiveSessions() {
        return new ArrayList<>(sessionLogs.keySet());
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || "current".equals(sessionId)) {
            String currentSessionId = activeSessionId.get();
            if (currentSessionId != null && !currentSessionId.isBlank()) {
                return currentSessionId;
            }
        }
        return sessionId;
    }

    private String formatArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object arg = args[i];
            if (arg == null) {
                sb.append("null");
            } else if (arg instanceof String str) {
                sb.append(str.length() > 120 ? str.substring(0, 120) + "..." : str);
            } else {
                sb.append(arg);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private AgentExecutionStep buildToolStep(ToolCallLog logEntry) {
        long endTime = logEntry.getTimestamp() != null
            ? logEntry.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            : System.currentTimeMillis();
        long duration = logEntry.getDuration() != null ? logEntry.getDuration() : 0L;

        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("tool_call");
        step.setStepName("toolCall");
        step.setSourceType("tool");
        step.setDataSource("tool");
        step.setStatus(logEntry.isPermitted() ? "SUCCESS" : "FAILED");
        step.setToolName(logEntry.getToolName());
        step.setDescription(logEntry.isPermitted()
            ? "调用工具 " + logEntry.getToolName()
            : "工具调用失败 " + logEntry.getToolName());
        step.setInputSummary(logEntry.getArguments());
        step.setOutputSummary(logEntry.isPermitted() ? null : logEntry.getErrorMessage());
        step.setExecutionTimeMs(duration);
        step.setStartTime(Math.max(0L, endTime - duration));
        step.setEndTime(endTime);
        step.setTimestamp(endTime);
        return step;
    }
}
