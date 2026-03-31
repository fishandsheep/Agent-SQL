package com.sqlagent.controller;

import com.sqlagent.model.WorkloadOptimizationRequest;
import com.sqlagent.model.WorkloadOptimizationResponse;
import com.sqlagent.service.WorkloadOptimizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkloadController {

    private final WorkloadOptimizationService workloadOptimizationService;

    @PostMapping("/analyze/workload")
    public ResponseEntity<WorkloadOptimizationResponse> optimizeWorkload(@RequestBody WorkloadOptimizationRequest request) {
        log.info("[workload] request received: sqlCount={}", request.getSqls() != null ? request.getSqls().size() : 0);

        if (!request.isValid()) {
            WorkloadOptimizationResponse response = new WorkloadOptimizationResponse();
            response.setInputSqls(request.getSqls());
            response.setSuccess(false);
            response.setErrorMessage(request.getValidationError());
            return ResponseEntity.badRequest().body(response);
        }

        WorkloadOptimizationResponse response = workloadOptimizationService.optimize(request);
        return ResponseEntity.ok(response);
    }
}