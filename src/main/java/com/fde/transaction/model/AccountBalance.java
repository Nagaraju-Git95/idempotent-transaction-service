package com.fde.transaction.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "account_balances")
public class AccountBalance {

    @Id
    private String accountId;

    private BigDecimal balance = BigDecimal.ZERO;

    private LocalDateTime lastUpdated = LocalDateTime.now();

    public AccountBalance() {}

    public AccountBalance(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
