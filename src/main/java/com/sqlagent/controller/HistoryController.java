package com.sqlagent.controller;

import com.sqlagent.model.HistoryDetailResult;
import com.sqlagent.model.HistoryListResult;
import com.sqlagent.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/history/list")
    public ResponseEntity<HistoryListResult> getHistoryList(
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String dateFrom,
        @RequestParam(required = false) String dateTo
    ) {
        LocalDateTime from = parseDateFrom(dateFrom);
        LocalDateTime to = parseDateTo(dateTo);
        log.info("[history] list requested: limit={}, from={}, to={}", limit, from, to);
        return ResponseEntity.ok(historyService.getHistoryList(limit, from, to));
    }

    @GetMapping("/history/{id}")
    public ResponseEntity<HistoryDetailResult> getHistoryDetail(@PathVariable Long id) {
        log.info("[history] detail requested: id={}", id);
        return ResponseEntity.ok(historyService.getHistoryDetail(id));
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Map<String, Object>> deleteHistory(@PathVariable Long id) {
        log.info("[history] delete requested: id={}", id);
        boolean success = historyService.deleteHistory(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "删除成功" : "删除失败");
        return ResponseEntity.ok(result);
    }

    private LocalDateTime parseDateFrom(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date).atStartOfDay();
    }

    private LocalDateTime parseDateTo(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date).plusDays(1).atStartOfDay().minusSeconds(1);
    }
}
