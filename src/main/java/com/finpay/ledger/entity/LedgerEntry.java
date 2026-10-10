
package com.finpay.ledger.entity;

import com.finpay.wallet.entity.Wallet;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "ledger_entries",
    indexes = {
        @Index(
            name = "idx_ledger_entries_transaction_id",
            columnList = "transaction_id"
        ),
        @Index(
            name = "idx_ledger_entries_wallet_id_created_at",
            columnList = "wallet_id, created_at"
        )
    }
)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private LedgerEntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
        // Required by JPA.
    }

    public LedgerEntry(
        Wallet wallet,
        LedgerEntryType entryType,
        BigDecimal amount,
        String currency
    ) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                "Ledger entry amount must be positive"
            );
        }

        this.wallet = wallet;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = OffsetDateTime.now();
    }

    void setTransaction(LedgerTransaction transaction) {
        this.transaction = transaction;
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}