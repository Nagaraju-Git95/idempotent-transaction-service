package com.fde.transaction.scheduler;

import com.fde.transaction.service.TransactionProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransactionProcessorScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessorScheduler.class);

    private final TransactionProcessorService processorService;

    public TransactionProcessorScheduler(TransactionProcessorService processorService) {
        this.processorService = processorService;
    }

    @Scheduled(fixedDelayString = "${transaction.processor.interval.ms:5000}")
    public void runProcessingCycle() {
        log.debug("Scheduler triggered — starting processing cycle.");
        processorService.processPending();
    }
}
