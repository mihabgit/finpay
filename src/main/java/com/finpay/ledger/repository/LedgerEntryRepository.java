
package com.finpay.ledger.repository;

import com.finpay.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository
    extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByWalletIdOrderByCreatedAtDesc(UUID walletId);

    List<LedgerEntry> findAllByTransactionId(UUID transactionId);
}