
package com.finpay.ledger.service;

import com.finpay.ledger.entity.LedgerEntry;
import com.finpay.ledger.entity.LedgerEntryType;
import com.finpay.ledger.entity.LedgerTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerValidationService {

    public void validateBalanced(List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction requires at least two entries");
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() == LedgerEntryType.DEBIT) {
                totalDebits = totalDebits.add(entry.getAmount());
            } else if (entry.getEntryType() == LedgerEntryType.CREDIT) {
                totalCredits = totalCredits.add(entry.getAmount());
            }
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalArgumentException("Ledger transaction is not balanced");
        }
    }

    public void validateBalanced(LedgerTransaction transaction) {
        validateBalanced(transaction.getEntries());
    }
}