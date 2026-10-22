package com.fde.transaction.service;

import com.fde.transaction.model.*;
import com.fde.transaction.repository.AccountBalanceRepository;
import com.fde.transaction.repository.TransactionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class TransactionProcessorService {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessorService.class);

    private final TransactionRequestRepository transactionRepo;
    private final AccountBalanceRepository accountBalanceRepo;

    @Value("${transaction.processor.max-retries:3}")
    private int maxRetries;

    // Simulated transient failure for specific transactionId (for demo purposes)
    private static final Set<String> SIMULATED_TRANSIENT_FAILURES = Set.of("TXN-2005");
    private final Map<String, Integer> simulatedFailureAttempts = new HashMap<>();

    public TransactionProcessorService(TransactionRequestRepository transactionRepo,
                                       AccountBalanceRepository accountBalanceRepo) {
        this.transactionRepo = transactionRepo;
        this.accountBalanceRepo = accountBalanceRepo;
    }

    /**
     * Submit a new transaction request. Detects duplicates by requestId before persisting.
     */
    @Transactional
    public TransactionRequest submit(TransactionRequest request) {
        // Duplicate detection: same requestId already exists
        if (transactionRepo.existsByRequestId(request.getRequestId())) {
            log.warn("Duplicate requestId detected: {} — marking as DUPLICATE", request.getRequestId());
            request.setStatus(TransactionStatus.DUPLICATE);
            return request; // do not persist again, just return duplicate signal
        }

        request.setStatus(TransactionStatus.RECEIVED);
        TransactionRequest saved = transactionRepo.save(request);
        log.info("Transaction submitted: transactionId={} requestId={} sequence={} status={}",
                saved.getTransactionId(), saved.getRequestId(), saved.getSequenceNumber(), saved.getStatus());
        return saved;
    }

    /**
     * Called by the scheduler. Processes all RECEIVED and RETRY_PENDING transactions.
     */
    @Transactional
    public void processPending() {
        List<TransactionRequest> pending = transactionRepo.findByStatusIn(
                List.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING));

        if (pending.isEmpty()) {
            log.debug("No pending transactions to process.");
            return;
        }

        log.info("Processing {} pending transaction(s).", pending.size());

        // Group by accountId and sort by sequenceNumber for ordered processing
        Map<String, List<TransactionRequest>> byAccount = new LinkedHashMap<>();
        for (TransactionRequest tx : pending) {
            byAccount.computeIfAbsent(tx.getAccountId(), k -> new ArrayList<>()).add(tx);
        }

        for (Map.Entry<String, List<TransactionRequest>> entry : byAccount.entrySet()) {
            List<TransactionRequest> accountTxns = entry.getValue();
            accountTxns.sort(Comparator.comparingInt(TransactionRequest::getSequenceNumber));
            processAccountTransactions(entry.getKey(), accountTxns);
        }
    }

    /**
     * Sequence-based out-of-order handling:
     * For each account, find the next expected sequence. If the lowest available
     * sequence matches, process it. If there is a gap, leave later ones as RECEIVED
     * (they will be picked up once the gap is filled).
     */
    private void processAccountTransactions(String accountId, List<TransactionRequest> sorted) {
        // Find the next expected sequence for this account
        int nextExpected = getNextExpectedSequence(accountId);

        for (TransactionRequest tx : sorted) {
            if (tx.getSequenceNumber() < nextExpected) {
                // Already processed a request with a higher or equal sequence — mark duplicate
                log.warn("Out-of-order stale request: transactionId={} seq={} expected>={}. Marking DUPLICATE.",
                        tx.getTransactionId(), tx.getSequenceNumber(), nextExpected);
                tx.setStatus(TransactionStatus.DUPLICATE);
                tx.setFailureReason("Stale sequence: expected >= " + nextExpected);
                transactionRepo.save(tx);
                continue;
            }

            if (tx.getSequenceNumber() > nextExpected) {
                // Gap detected — leave this and subsequent ones pending
                log.info("Sequence gap for accountId={}: expected={} got={}. Leaving RECEIVED for later processing.",
                        accountId, nextExpected, tx.getSequenceNumber());
                break;
            }

            // Sequence matches — process it
            processSingle(tx);
            nextExpected++;
        }
    }

    /**
     * Determines the next expected sequence for an account.
     * Finds the highest consecutive PROCESSED sequence starting from 1, then adds 1.
     */
    private int getNextExpectedSequence(String accountId) {
        List<TransactionRequest> all = transactionRepo.findByAccountIdOrderBySequenceNumberAsc(accountId);
        int next = 1;
        for (TransactionRequest tx : all) {
            if (tx.getStatus() == TransactionStatus.PROCESSED && tx.getSequenceNumber() == next) {
                next++;
            } else if (tx.getStatus() != TransactionStatus.PROCESSED
                    && tx.getStatus() != TransactionStatus.DUPLICATE
                    && tx.getSequenceNumber() == next) {
                // This sequence is pending — stop here, this is what we'll process next
                break;
            }
        }
        return next;
    }

    /**
     * Process a single transaction with retry and idempotency protection.
     */
    @Transactional
    public void processSingle(TransactionRequest tx) {
        tx.setStatus(TransactionStatus.PROCESSING);
        transactionRepo.save(tx);

        try {
            simulateTransientFailureIfNeeded(tx);
            applyBalanceUpdate(tx);
            tx.setStatus(TransactionStatus.PROCESSED);
            tx.setProcessedAt(LocalDateTime.now());
            tx.setFailureReason(null);
            log.info("PROCESSED: transactionId={} requestId={} accountId={} type={} amount={}",
                    tx.getTransactionId(), tx.getRequestId(), tx.getAccountId(),
                    tx.getTransactionType(), tx.getAmount());
        } catch (TransientProcessingException e) {
            handleRetry(tx, e.getMessage());
        } catch (Exception e) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Unexpected error: " + e.getMessage());
            log.error("FAILED: transactionId={} reason={}", tx.getTransactionId(), e.getMessage());
        }

        transactionRepo.save(tx);
    }

    private void applyBalanceUpdate(TransactionRequest tx) {
        AccountBalance account = accountBalanceRepo.findById(tx.getAccountId())
                .orElseGet(() -> new AccountBalance(tx.getAccountId()));

        switch (tx.getTransactionType()) {
            case CREDIT -> account.setBalance(account.getBalance().add(tx.getAmount()));
            case DEBIT -> account.setBalance(account.getBalance().subtract(tx.getAmount()));
            case REVERSAL -> account.setBalance(account.getBalance().add(tx.getAmount()));
        }

        account.setLastUpdated(LocalDateTime.now());
        accountBalanceRepo.save(account);
        log.debug("Balance updated for accountId={} newBalance={}", tx.getAccountId(), account.getBalance());
    }

    private void simulateTransientFailureIfNeeded(TransactionRequest tx) {
        if (SIMULATED_TRANSIENT_FAILURES.contains(tx.getTransactionId())) {
            int attempts = simulatedFailureAttempts.getOrDefault(tx.getTransactionId(), 0);
            if (attempts < 1) {
                simulatedFailureAttempts.put(tx.getTransactionId(), attempts + 1);
                throw new TransientProcessingException("Simulated transient failure for " + tx.getTransactionId());
            }
            // Succeeds on second attempt
            simulatedFailureAttempts.remove(tx.getTransactionId());
        }
    }

    private void handleRetry(TransactionRequest tx, String reason) {
        int retries = tx.getRetryCount() + 1;
        tx.setRetryCount(retries);
        tx.setFailureReason(reason);

        if (retries >= maxRetries) {
            tx.setStatus(TransactionStatus.FAILED);
            log.error("FAILED after {} retries: transactionId={} reason={}", retries, tx.getTransactionId(), reason);
        } else {
            tx.setStatus(TransactionStatus.RETRY_PENDING);
            log.warn("RETRY_PENDING ({}/{}): transactionId={} reason={}", retries, maxRetries, tx.getTransactionId(), reason);
        }
    }

    public Map<String, Long> getSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            summary.put(status.name(), transactionRepo.countByStatus(status));
        }
        return summary;
    }

    public List<TransactionRequest> getAllTransactions() {
        return transactionRepo.findAll();
    }
}
