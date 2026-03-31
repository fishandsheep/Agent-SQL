package com.sqlagent.controller;

import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.MultiPlanRequest;
import com.sqlagent.model.MultiPlanResponse;
import com.sqlagent.service.ExecutionTraceService;
import com.sqlagent.service.UnifiedOptimizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OptimizationController {

    private final UnifiedOptimizationService unifiedOptimizationService;
    private final ExecutionTraceService executionTraceService;

    @PostMapping("/optimize")
    public ResponseEntity<MultiPlanResponse> optimize(@RequestBody MultiPlanRequest request) {
        log.info("[optimize] request received");

        if (request.getSql() == null || request.getSql().trim().isEmpty()) {
            MultiPlanResponse response = new MultiPlanResponse();
            response.setSuccess(false);
            response.setError("SQL不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        MultiPlanResponse response = unifiedOptimizationService.optimize(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/optimize/stream")
    public SseEmitter optimizeStream(@RequestBody MultiPlanRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        String streamSessionId = UUID.randomUUID().toString().substring(0, 8);
        AtomicLong eventSeq = new AtomicLong(0);

        CompletableFuture.runAsync(() -> {
            CompletableFuture<MultiPlanResponse> future = null;
            try {
                executionTraceService.registerListener(streamSessionId, traceEvent -> {
                    try {
                        String eventName = "phase".equalsIgnoreCase(traceEvent.getEventType())
                            ? "phase_update"
                            : "trace_step";
                        emitter.send(SseEmitter.event()
                            .name(eventName)
                            .data(buildStreamEvent(
                                streamSessionId,
                                eventSeq.incrementAndGet(),
                                eventName,
                                traceEvent.getStep().getSourceType(),
                                traceEvent.getStep().getStepType(),
                                buildStepPayload(traceEvent.getStep())
                            )));
                    } catch (Exception sendError) {
                        log.debug("[optimize-stream] listener send failed: sessionId={}", streamSessionId, sendError);
                    }
                });

                future = CompletableFuture.supplyAsync(() ->
                    unifiedOptimizationService.optimize(request, streamSessionId));

                while (!future.isDone()) {
                    emitter.send(SseEmitter.event().name("heartbeat").data(buildStreamEvent(
                        streamSessionId,
                        eventSeq.incrementAndGet(),
                        "heartbeat",
                        "system",
                        "progress",
                        Map.of("phase", "running", "message", "Optimization still running")
                    )));
                    TimeUnit.SECONDS.sleep(2);
                }

                MultiPlanResponse response = future.get();
                emitter.send(SseEmitter.event().name("final_result").data(buildStreamEvent(
                    streamSessionId,
                    eventSeq.incrementAndGet(),
                    "final_result",
                    "system",
                    "result",
                    response
                )));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                executionTraceService.clearListeners(streamSessionId);
            }
        });

        return emitter;
    }

    private Map<String, Object> buildStepPayload(AgentExecutionStep step) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stepNumber", step.getStepNumber());
        payload.put("toolName", step.getToolName());
        payload.put("stepName", step.getStepName());
        payload.put("status", step.getStatus());
        payload.put("durationMs", step.getExecutionTimeMs());
        payload.put("description", step.getDescription());
        payload.put("details", step.getDetails());
        payload.put("inputSummary", step.getInputSummary());
        payload.put("outputSummary", step.getOutputSummary());
        payload.put("timestamp", step.getTimestamp());
        payload.put("startTime", step.getStartTime());
        payload.put("endTime", step.getEndTime());
        return payload;
    }

    private Map<String, Object> buildStreamEvent(
        String sessionId,
        long eventId,
        String eventType,
        String sourceType,
        String stepType,
        Object payload
    ) {
        Map<String, Object> event = new HashMap<>();
        event.put("sessionId", sessionId);
        event.put("eventId", eventId);
        event.put("ts", System.currentTimeMillis());
        event.put("eventType", eventType);
        event.put("sourceType", sourceType);
        event.put("stepType", stepType);
        event.put("payload", payload);
        return event;
    }
}