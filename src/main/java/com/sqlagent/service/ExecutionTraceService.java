package com.sqlagent.service;

import com.sqlagent.model.AgentExecutionStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class ExecutionTraceService {

    private final Map<String, List<AgentExecutionStep>> sessionSteps = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sessionCounters = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<TraceEvent>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, TracePhaseState> phaseStates = new ConcurrentHashMap<>();

    public void registerListener(String sessionId, Consumer<TraceEvent> listener) {
        listeners.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void clearListeners(String sessionId) {
        listeners.remove(sessionId);
    }

    public AgentExecutionStep recordStep(String sessionId, AgentExecutionStep step) {
        AgentExecutionStep normalized = normalizeStep(sessionId, step);
        sessionSteps.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(normalized);
        updatePhaseState(sessionId, normalized);
        publish(sessionId, TraceEvent.fromStep(sessionId, normalized));
        return normalized;
    }

    public AgentExecutionStep recordPhase(
        String sessionId,
        String phaseCode,
        String name,
        String status,
        String description,
        String sourceType,
        String inputSummary,
        String outputSummary,
        Long durationMs
    ) {
        long now = System.currentTimeMillis();
        long duration = durationMs != null ? durationMs : 0L;

        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("phase");
        step.setStepName(name);
        step.setToolName(phaseCode);
        step.setSourceType(sourceType != null ? sourceType : "system");
        step.setDataSource("system");
        step.setStatus(status != null ? status : "RUNNING");
        step.setDescription(description);
        step.setInputSummary(inputSummary);
        step.setOutputSummary(outputSummary);
        step.setExecutionTimeMs(duration);
        step.setStartTime(now - duration);
        step.setEndTime(now);
        step.setTimestamp(now);
        return recordStep(sessionId, step);
    }

    public List<AgentExecutionStep> getSessionSteps(String sessionId) {
        return sessionSteps.getOrDefault(sessionId, List.of()).stream()
            .sorted(Comparator
                .comparingLong(AgentExecutionStep::getStartTime)
                .thenComparingInt(AgentExecutionStep::getStepNumber))
            .toList();
    }

    public TracePhaseState getPhaseState(String sessionId) {
        return phaseStates.get(sessionId);
    }

    public void clearSession(String sessionId) {
        sessionSteps.remove(sessionId);
        sessionCounters.remove(sessionId);
        listeners.remove(sessionId);
        phaseStates.remove(sessionId);
    }

    private AgentExecutionStep normalizeStep(String sessionId, AgentExecutionStep step) {
        AgentExecutionStep normalized = copy(step);
        long now = System.currentTimeMillis();

        if (normalized.getStepNumber() <= 0) {
            normalized.setStepNumber(sessionCounters
                .computeIfAbsent(sessionId, key -> new AtomicInteger())
                .incrementAndGet());
        }
        if (normalized.getStartTime() <= 0) {
            long endTime = normalized.getEndTime() > 0 ? normalized.getEndTime() : now;
            long duration = Math.max(0L, normalized.getExecutionTimeMs());
            normalized.setStartTime(Math.max(0L, endTime - duration));
        }
        if (normalized.getEndTime() <= 0) {
            long endTime = normalized.getStartTime() + Math.max(0L, normalized.getExecutionTimeMs());
            normalized.setEndTime(endTime > 0 ? endTime : now);
        }
        if (normalized.getTimestamp() <= 0) {
            normalized.setTimestamp(normalized.getEndTime());
        }
        if (normalized.getExecutionTimeMs() <= 0 && normalized.getEndTime() >= normalized.getStartTime()) {
            normalized.setExecutionTimeMs(Math.max(0L, normalized.getEndTime() - normalized.getStartTime()));
        }
        if (normalized.getSourceType() == null || normalized.getSourceType().isBlank()) {
            normalized.setSourceType("system");
        }
        if (normalized.getDataSource() == null || normalized.getDataSource().isBlank()) {
            normalized.setDataSource(normalized.getSourceType());
        }
        if (normalized.getStatus() == null || normalized.getStatus().isBlank()) {
            normalized.setStatus("SUCCESS");
        }
        if (normalized.getSubSteps() == null) {
            normalized.setSubSteps(new ArrayList<>());
        }
        return normalized;
    }

    private AgentExecutionStep copy(AgentExecutionStep source) {
        AgentExecutionStep target = new AgentExecutionStep();
        target.setStepNumber(source.getStepNumber());
        target.setStepType(source.getStepType());
        target.setSourceType(source.getSourceType());
        target.setDataSource(source.getDataSource());
        target.setStatus(source.getStatus());
        target.setToolName(source.getToolName());
        target.setStepName(source.getStepName());
        target.setToolIcon(source.getToolIcon());
        target.setDescription(source.getDescription());
        target.setDetails(source.getDetails());
        target.setInputSummary(source.getInputSummary());
        target.setOutputSummary(source.getOutputSummary());
        target.setExplain(source.getExplain());
        target.setExecutionTimeMs(source.getExecutionTimeMs());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setTimestamp(source.getTimestamp());
        target.setSubSteps(source.getSubSteps() != null ? new ArrayList<>(source.getSubSteps()) : new ArrayList<>());
        return target;
    }

    private void updatePhaseState(String sessionId, AgentExecutionStep step) {
        if (!"phase".equalsIgnoreCase(step.getStepType())) {
            return;
        }

        phaseStates.put(sessionId, TracePhaseState.builder()
            .phaseCode(step.getToolName())
            .phaseName(step.getStepName())
            .status(step.getStatus())
            .description(step.getDescription())
            .startedAt(step.getStartTime())
            .updatedAt(step.getEndTime())
            .build());
    }

    private void publish(String sessionId, TraceEvent event) {
        for (Consumer<TraceEvent> listener : listeners.getOrDefault(sessionId, List.of())) {
            listener.accept(event);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceEvent {
        private String sessionId;
        private String eventType;
        private long timestamp;
        private AgentExecutionStep step;

        public static TraceEvent fromStep(String sessionId, AgentExecutionStep step) {
            String eventType = "phase".equalsIgnoreCase(step.getStepType()) ? "phase" : "step";
            return TraceEvent.builder()
                .sessionId(sessionId)
                .eventType(eventType)
                .timestamp(step.getTimestamp())
                .step(step)
                .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TracePhaseState {
        private String phaseCode;
        private String phaseName;
        private String status;
        private String description;
        private long startedAt;
        private long updatedAt;
    }
}
