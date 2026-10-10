
package com.finpay.wallet.repository;

import com.finpay.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findAllByUserId(UUID userId);

    @EntityGraph(attributePaths = "user")
    Optional<Wallet> findByIdAndUserId(UUID walletId, UUID userId);

    boolean existsByUserIdAndCurrencyIgnoreCase(UUID userId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :walletId")
    Optional<Wallet> findByIdForUpdate(
            @Param("walletId") UUID walletId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT w FROM Wallet w
    WHERE w.id = :walletId AND w.user.id = :userId
    """)
    Optional<Wallet> findByIdAndUserIdForUpdate(
            @Param("walletId") UUID walletId,
            @Param("userId") UUID userId
    );

}