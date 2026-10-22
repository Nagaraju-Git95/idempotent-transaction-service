package com.fde.transaction.repository;

import com.fde.transaction.model.TransactionRequest;
import com.fde.transaction.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRequestRepository extends JpaRepository<TransactionRequest, Long> {

    Optional<TransactionRequest> findByRequestId(String requestId);

    Optional<TransactionRequest> findByTransactionId(String transactionId);

    List<TransactionRequest> findByStatusIn(List<TransactionStatus> statuses);

    List<TransactionRequest> findByAccountIdOrderBySequenceNumberAsc(String accountId);

    boolean existsByRequestId(String requestId);

    long countByStatus(TransactionStatus status);
}
