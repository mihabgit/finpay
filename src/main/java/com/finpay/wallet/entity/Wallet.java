
package com.finpay.wallet.entity;

import com.finpay.identity.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "wallets",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_wallets_user_currency",
            columnNames = {"user_id", "currency"}
        )
    },
    indexes = {
        @Index(name = "idx_wallets_user_id", columnList = "user_id")
    }
)
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO.setScale(4);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Wallet() {
        // Required by JPA.
    }

    public Wallet(User user, String currency) {
        this.user = user;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(4);
        this.status = WalletStatus.ACTIVE;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public WalletStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }


    public void debit(BigDecimal amount) {
        validatePositiveAmount(amount);

        if (status != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active");
        }

        if (balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }

        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        validatePositiveAmount(amount);

        if (status != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active");
        }

        balance = balance.add(amount);
    }

    private void validatePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        if (amount.scale() > 4) {
            throw new IllegalArgumentException("Amount cannot have more than 4 decimal places");
        }
    }
}