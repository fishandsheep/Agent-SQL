package com.sqlagent.aspect;

import com.sqlagent.model.MultiPlanRequest;
import com.sqlagent.model.MultiPlanResponse;
import com.sqlagent.model.SqlValidationResponse;
import com.sqlagent.model.WorkloadOptimizationRequest;
import com.sqlagent.model.WorkloadOptimizationResponse;
import com.sqlagent.service.SqlQueryValidationService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Aspect
@Component
@RequiredArgsConstructor
public class SqlRequestValidationAspect {

    private final SqlQueryValidationService sqlQueryValidationService;

    @Around("execution(* com.sqlagent.controller.OptimizationController.optimize(..)) && args(request)")
    public Object validateOptimizeRequest(ProceedingJoinPoint joinPoint, MultiPlanRequest request) throws Throwable {
        SqlValidationResponse result = sqlQueryValidationService.validateSingle(
            request != null ? request.getSql() : null,
            "SQL"
        );
        if (!result.isValid()) {
            MultiPlanResponse response = new MultiPlanResponse();
            response.setSuccess(false);
            response.setError(result.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
        return joinPoint.proceed();
    }

    @Around("execution(* com.sqlagent.controller.OptimizationController.optimizeStream(..)) && args(request)")
    public Object validateOptimizeStreamRequest(ProceedingJoinPoint joinPoint, MultiPlanRequest request) throws Throwable {
        SqlValidationResponse result = sqlQueryValidationService.validateSingle(
            request != null ? request.getSql() : null,
            "SQL"
        );
        if (!result.isValid()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "success", false,
                    "error", result.getMessage()
                )));
            } finally {
                emitter.complete();
            }
            return emitter;
        }
        return joinPoint.proceed();
    }

    @Around("execution(* com.sqlagent.controller.WorkloadController.optimizeWorkload(..)) && args(request)")
    public Object validateWorkloadRequest(ProceedingJoinPoint joinPoint, WorkloadOptimizationRequest request) throws Throwable {
        SqlValidationResponse result = sqlQueryValidationService.validateBatch(
            request != null ? request.getSqls() : null,
            10
        );
        if (!result.isValid()) {
            WorkloadOptimizationResponse response = new WorkloadOptimizationResponse();
            response.setInputSqls(request != null ? request.getSqls() : null);
            response.setSuccess(false);
            response.setErrorMessage(result.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
        return joinPoint.proceed();
    }
}
