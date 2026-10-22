package com.fde.transaction.config;

import com.fde.transaction.model.TransactionRequest;
import com.fde.transaction.model.TransactionType;
import com.fde.transaction.service.TransactionProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Loads the assessment test data on startup as per section 9.8 of the problem statement.
 * Submits transactions in the prescribed arrival order (out-of-order by design).
 */
@Component
@org.springframework.context.annotation.Profile("!test")
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final TransactionProcessorService processorService;

    public DataLoader(TransactionProcessorService processorService) {
        this.processorService = processorService;
    }

    @Override
    public void run(String... args) {
        log.info("Loading assessment test data...");

        List<TransactionRequest> testData = List.of(
            build("TXN-2002", "REQ-2",     2, "ACC-201", TransactionType.DEBIT,   new BigDecimal("40"),  "USD"),
            build("TXN-2001", "REQ-1",     1, "ACC-201", TransactionType.CREDIT,  new BigDecimal("100"), "USD"),
            build("TXN-2002", "REQ-2-DUP", 2, "ACC-201", TransactionType.DEBIT,   new BigDecimal("40"),  "USD"),
            build("TXN-2003", "REQ-3",     3, "ACC-201", TransactionType.CREDIT,  new BigDecimal("25"),  "USD"),
            build("TXN-2004", "REQ-4",     4, "ACC-202", TransactionType.DEBIT,   new BigDecimal("-10"), "USD"), // invalid amount
            build("TXN-2005", "REQ-5",     5, "ACC-203", TransactionType.CREDIT,  new BigDecimal("500"), "USD")
        );

        for (TransactionRequest req : testData) {
            try {
                processorService.submit(req);
            } catch (Exception e) {
                log.warn("Skipped invalid request {}: {}", req.getRequestId(), e.getMessage());
            }
        }

        log.info("Test data loaded. Scheduler will process transactions every 5 seconds.");
    }

    private TransactionRequest build(String txId, String reqId, int seq,
                                     String accountId, TransactionType type,
                                     BigDecimal amount, String currency) {
        TransactionRequest r = new TransactionRequest();
        r.setTransactionId(txId);
        r.setRequestId(reqId);
        r.setSequenceNumber(seq);
        r.setAccountId(accountId);
        r.setTransactionType(type);
        r.setAmount(amount);
        r.setCurrency(currency);
        return r;
    }
}
