package com.fde.transaction.controller;

import com.fde.transaction.model.TransactionRequest;
import com.fde.transaction.model.TransactionStatus;
import com.fde.transaction.service.TransactionProcessorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionProcessorService processorService;

    public TransactionController(TransactionProcessorService processorService) {
        this.processorService = processorService;
    }

    @PostMapping
    public ResponseEntity<TransactionRequest> submit(@Valid @RequestBody TransactionRequest request) {
        TransactionRequest result = processorService.submit(request);
        if (result.getStatus() == TransactionStatus.DUPLICATE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<TransactionRequest>> submitBatch(@RequestBody List<@Valid TransactionRequest> requests) {
        List<TransactionRequest> results = requests.stream()
                .map(processorService::submit)
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping
    public ResponseEntity<List<TransactionRequest>> getAll() {
        return ResponseEntity.ok(processorService.getAllTransactions());
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Long>> getSummary() {
        return ResponseEntity.ok(processorService.getSummary());
    }

    @PostMapping("/process")
    public ResponseEntity<String> triggerProcessing() {
        processorService.processPending();
        return ResponseEntity.ok("Processing cycle triggered.");
    }
}
