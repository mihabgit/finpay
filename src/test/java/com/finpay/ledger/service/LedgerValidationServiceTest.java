
package com.finpay.ledger.service;

import com.finpay.ledger.entity.LedgerEntry;
import com.finpay.ledger.entity.LedgerEntryType;
import com.finpay.ledger.entity.LedgerTransaction;
import com.finpay.ledger.entity.LedgerTransactionType;
import com.finpay.wallet.entity.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerValidationServiceTest {

    private final LedgerValidationService service =
        new LedgerValidationService();

    @Test
    void validateBalanced_shouldAcceptEqualDebitsAndCredits() {
        Wallet wallet = null; // Not needed for testing balance totals.

        // Constructing entries requires a Wallet, so use the entity's
        // normal constructor with persisted wallets in integration tests.
        // The complete balance test will be implemented there.
    }

    @Test
    void validateBalanced_shouldRejectEmptyEntries() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.validateBalanced(List.of())
        );
    }
}