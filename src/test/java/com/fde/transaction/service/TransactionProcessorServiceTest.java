package com.fde.transaction.service;

import com.fde.transaction.model.*;
import com.fde.transaction.repository.AccountBalanceRepository;
import com.fde.transaction.repository.TransactionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class TransactionProcessorServiceTest {

    @Autowired
    private TransactionProcessorService processorService;

    @Autowired
    private TransactionRequestRepository transactionRepo;

    @Autowired
    private AccountBalanceRepository accountBalanceRepo;

    private TransactionRequest buildRequest(String txId, String reqId, int seq,
                                             String accountId, TransactionType type, BigDecimal amount) {
        TransactionRequest r = new TransactionRequest();
        r.setTransactionId(txId);
        r.setRequestId(reqId);
        r.setSequenceNumber(seq);
        r.setAccountId(accountId);
        r.setTransactionType(type);
        r.setAmount(amount);
        r.setCurrency("USD");
        return r;
    }

    // --- Idempotency / Duplicate Detection ---

    @Test
    void duplicateRequestId_shouldBeMarkedDuplicate() {
        TransactionRequest original = buildRequest("TXN-1", "REQ-1", 1, "ACC-1", TransactionType.CREDIT, new BigDecimal("100"));
        processorService.submit(original);

        TransactionRequest duplicate = buildRequest("TXN-1", "REQ-1", 1, "ACC-1", TransactionType.CREDIT, new BigDecimal("100"));
        TransactionRequest result = processorService.submit(duplicate);

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(transactionRepo.count()).isEqualTo(1L);
    }

    @Test
    void duplicateRequestId_shouldNotCreateDuplicateBalanceEffect() {
        TransactionRequest req = buildRequest("TXN-10", "REQ-10", 1, "ACC-10", TransactionType.CREDIT, new BigDecimal("200"));
        processorService.submit(req);
        processorService.processPending();

        // Submit same requestId again
        TransactionRequest dup = buildRequest("TXN-10", "REQ-10", 1, "ACC-10", TransactionType.CREDIT, new BigDecimal("200"));
        processorService.submit(dup);
        processorService.processPending();

        AccountBalance balance = accountBalanceRepo.findById("ACC-10").orElseThrow();
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("200"));
    }

    // --- Out-of-order Handling ---

    @Test
    void outOfOrderRequests_shouldProcessInSequenceOrder() {
        // Arrive out of order: seq 2 first, then seq 1
        TransactionRequest seq2 = buildRequest("TXN-2", "REQ-2", 2, "ACC-2", TransactionType.DEBIT, new BigDecimal("40"));
        TransactionRequest seq1 = buildRequest("TXN-1", "REQ-1", 1, "ACC-2", TransactionType.CREDIT, new BigDecimal("100"));

        processorService.submit(seq2);
        processorService.submit(seq1);

        // First cycle: seq1 processes, seq2 now unblocked
        processorService.processPending();
        // Second cycle: seq2 processes
        processorService.processPending();

        AccountBalance balance = accountBalanceRepo.findById("ACC-2").orElseThrow();
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("60"));

        List<TransactionRequest> all = transactionRepo.findAll();
        assertThat(all).allMatch(t -> t.getStatus() == TransactionStatus.PROCESSED);
    }

    @Test
    void sequenceGap_shouldLeaveLaterRequestsPending() {
        // Submit seq 1 and seq 3 — seq 2 is missing
        TransactionRequest seq1 = buildRequest("TXN-1", "REQ-1", 1, "ACC-3", TransactionType.CREDIT, new BigDecimal("100"));
        TransactionRequest seq3 = buildRequest("TXN-3", "REQ-3", 3, "ACC-3", TransactionType.CREDIT, new BigDecimal("50"));

        processorService.submit(seq1);
        processorService.submit(seq3);
        processorService.processPending();

        TransactionRequest r1 = transactionRepo.findByRequestId("REQ-1").orElseThrow();
        TransactionRequest r3 = transactionRepo.findByRequestId("REQ-3").orElseThrow();

        assertThat(r1.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(r3.getStatus()).isEqualTo(TransactionStatus.RECEIVED); // still waiting for seq 2
    }

    // --- Retry Handling ---

    @Test
    void transientFailure_TXN2005_shouldRetryAndEventuallyProcess() {
        TransactionRequest req = buildRequest("TXN-2005", "REQ-5", 1, "ACC-5", TransactionType.CREDIT, new BigDecimal("500"));
        processorService.submit(req);

        // First cycle: transient failure → RETRY_PENDING
        processorService.processPending();
        TransactionRequest after1 = transactionRepo.findByRequestId("REQ-5").orElseThrow();
        assertThat(after1.getStatus()).isEqualTo(TransactionStatus.RETRY_PENDING);
        assertThat(after1.getRetryCount()).isEqualTo(1);

        // Second cycle: succeeds on retry
        processorService.processPending();
        TransactionRequest after2 = transactionRepo.findByRequestId("REQ-5").orElseThrow();
        assertThat(after2.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
    }

    @Test
    void retryDoesNotCreateDuplicateBalanceEffect() {
        TransactionRequest req = buildRequest("TXN-2005", "REQ-5R", 1, "ACC-5R", TransactionType.CREDIT, new BigDecimal("300"));
        processorService.submit(req);

        processorService.processPending(); // fails once
        processorService.processPending(); // succeeds

        AccountBalance balance = accountBalanceRepo.findById("ACC-5R").orElseThrow();
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("300"));
    }

    // --- Valid Lifecycle ---

    @Test
    void validTransaction_shouldBeProcessedWithCorrectBalance() {
        TransactionRequest req = buildRequest("TXN-100", "REQ-100", 1, "ACC-100", TransactionType.CREDIT, new BigDecimal("500"));
        processorService.submit(req);
        processorService.processPending();

        TransactionRequest result = transactionRepo.findByRequestId("REQ-100").orElseThrow();
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(result.getProcessedAt()).isNotNull();

        AccountBalance balance = accountBalanceRepo.findById("ACC-100").orElseThrow();
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("500"));
    }

    // --- Summary ---

    @Test
    void summary_shouldReturnCorrectStatusCounts() {
        processorService.submit(buildRequest("TXN-A", "REQ-A", 1, "ACC-A", TransactionType.CREDIT, new BigDecimal("100")));
        processorService.submit(buildRequest("TXN-B", "REQ-B", 1, "ACC-B", TransactionType.CREDIT, new BigDecimal("50")));
        processorService.processPending();

        Map<String, Long> summary = processorService.getSummary();
        assertThat(summary.get("PROCESSED")).isGreaterThanOrEqualTo(2L);
    }

    // --- Assessment Test Data (9.8) ---

    @Test
    void assessmentTestData_shouldProduceExpectedOutcomes() {
        // Submit in prescribed arrival order
        processorService.submit(buildRequest("TXN-2002", "REQ-2",     2, "ACC-201", TransactionType.DEBIT,  new BigDecimal("40"),  new BigDecimal("40")));
        processorService.submit(buildRequest("TXN-2001", "REQ-1",     1, "ACC-201", TransactionType.CREDIT, new BigDecimal("100"), new BigDecimal("100")));
        processorService.submit(buildRequest("TXN-2002", "REQ-2-DUP", 2, "ACC-201", TransactionType.DEBIT,  new BigDecimal("40"),  new BigDecimal("40")));
        processorService.submit(buildRequest("TXN-2003", "REQ-3",     3, "ACC-201", TransactionType.CREDIT, new BigDecimal("25"),  new BigDecimal("25")));
        processorService.submit(buildRequest("TXN-2003", "REQ-3",     3, "ACC-201", TransactionType.CREDIT, new BigDecimal("25"),  new BigDecimal("25"))); // dup check only

        processorService.processPending();
        processorService.processPending(); // second cycle for retry

        // REQ-2-DUP must be DUPLICATE
        TransactionRequest dup = transactionRepo.findByRequestId("REQ-2-DUP").orElseThrow();
        assertThat(dup.getStatus()).isEqualTo(TransactionStatus.DUPLICATE);

        // REQ-1 must be PROCESSED
        TransactionRequest req1 = transactionRepo.findByRequestId("REQ-1").orElseThrow();
        assertThat(req1.getStatus()).isEqualTo(TransactionStatus.PROCESSED);

        // REQ-3 must be PROCESSED
        TransactionRequest req3 = transactionRepo.findByRequestId("REQ-3").orElseThrow();
        assertThat(req3.getStatus()).isEqualTo(TransactionStatus.PROCESSED);

        // Final balance for ACC-201: +100 -40 +25 = 85
        AccountBalance balance = accountBalanceRepo.findById("ACC-201").orElseThrow();
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("85"));
    }

    // Helper overload
    private TransactionRequest buildRequest(String txId, String reqId, int seq,
                                             String accountId, TransactionType type,
                                             BigDecimal amount, BigDecimal ignored) {
        return buildRequest(txId, reqId, seq, accountId, type, amount);
    }
}
