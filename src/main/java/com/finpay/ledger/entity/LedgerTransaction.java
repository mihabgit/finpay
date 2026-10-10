
package com.finpay.ledger.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "ledger_transactions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_ledger_transactions_reference",
            columnNames = "reference"
        )
    },
    indexes = {
        @Index(
            name = "idx_ledger_transactions_created_at",
            columnList = "created_at"
        )
    }
)
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private LedgerTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerTransactionStatus status;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(
        mappedBy = "transaction",
        cascade = CascadeType.PERSIST
    )
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
        // Required by JPA.
    }

    public LedgerTransaction(
        String reference,
        LedgerTransactionType transactionType,
        String description
    ) {
        this.reference = reference;
        this.transactionType = transactionType;
        this.description = description;
        this.status = LedgerTransactionStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    public void addEntry(LedgerEntry entry) {
        entries.add(entry);
        entry.setTransaction(this);
    }

    public void markPosted() {
        if (status != LedgerTransactionStatus.PENDING) {
            throw new IllegalStateException("Only pending transactions can be posted");
        }
        status = LedgerTransactionStatus.POSTED;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public LedgerTransactionStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}