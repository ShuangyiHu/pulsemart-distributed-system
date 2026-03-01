package com.pulsemart.summarizer.api;

import com.pulsemart.summarizer.api.dto.SummaryResponse;
import com.pulsemart.summarizer.service.SummarizerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/summaries")
@RequiredArgsConstructor
public class SummarizerController {

    private final SummarizerService summarizerService;

    @GetMapping("/{orderId}")
    public ResponseEntity<SummaryResponse> getSummary(@PathVariable UUID orderId) {
        return summarizerService.getSummary(orderId)
                .map(SummaryResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<SummaryResponse> getAllSummaries() {
        return summarizerService.getAllSummaries().stream()
                .map(SummaryResponse::from)
                .toList();
    }
}
