
package com.finpay.idempotency.entity;

import jakarta.persistence.*;

import com.finpay.identity.entity.User;
import com.finpay.ledger.entity.LedgerTransaction;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "idempotency_records",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_idempotency_user_key",
            columnNames = {"user_id", "idempotency_key"}
        ),
        @UniqueConstraint(
            name = "uk_idempotency_transaction",
            columnNames = {"transaction_id"}
        )
    }
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_idempotency_records_user")
    )
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "transaction_id",
        foreignKey = @ForeignKey(
            name = "fk_idempotency_records_transaction"
        )
    )
    private LedgerTransaction transaction;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(
            User user,
            String idempotencyKey,
            String requestHash) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = OffsetDateTime.now();
    }

    public void associateTransaction(LedgerTransaction transaction) {
        if (this.transaction != null) {
            throw new IllegalStateException(
                    "Idempotency record already has a transaction"
            );
        }

        this.transaction = transaction;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}