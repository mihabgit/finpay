
package com.finpay.wallet.service;

import com.finpay.idempotency.entity.IdempotencyRecord;
import com.finpay.idempotency.repository.IdempotencyRecordRepository;
import com.finpay.idempotency.service.TransferRequestHasher;
import com.finpay.identity.entity.User;
import com.finpay.identity.repository.UserRepository;
import com.finpay.ledger.entity.LedgerEntry;
import com.finpay.ledger.entity.LedgerEntryType;
import com.finpay.ledger.entity.LedgerTransaction;
import com.finpay.ledger.entity.LedgerTransactionStatus;
import com.finpay.ledger.entity.LedgerTransactionType;
import com.finpay.ledger.repository.LedgerTransactionRepository;
import com.finpay.ledger.service.LedgerValidationService;
import com.finpay.wallet.dto.TransferResponse;
import com.finpay.wallet.entity.Wallet;
import com.finpay.wallet.entity.WalletStatus;
import com.finpay.wallet.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerValidationService ledgerValidationService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransferRequestHasher transferRequestHasher;

    public TransferService(
            WalletRepository walletRepository,
            UserRepository userRepository,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerValidationService ledgerValidationService,
            IdempotencyRecordRepository idempotencyRecordRepository,
            TransferRequestHasher transferRequestHasher
    ) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerValidationService = ledgerValidationService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transferRequestHasher = transferRequestHasher;
    }

    @Transactional
    public TransferResponse transfer(
            String authenticatedEmail,
            UUID senderWalletId,
            UUID receiverWalletId,
            BigDecimal amount,
            String idempotencyKey
    ) {
        validateRequest(senderWalletId, receiverWalletId, amount);
        idempotencyKey = validateIdempotencyKey(idempotencyKey);

        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);

        User senderUser = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        String requestHash = transferRequestHasher.hash(senderWalletId, receiverWalletId, amount);

        int reserved = idempotencyRecordRepository.reserve(
                UUID.randomUUID(),
                senderUser.getId(),
                idempotencyKey,
                requestHash,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        if (reserved == 0) {
            IdempotencyRecord existingRecord =
                    idempotencyRecordRepository
                            .findByUserIdAndIdempotencyKey(senderUser.getId(), idempotencyKey)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Idempotency record was not found after conflict"
                            ));

            if (!existingRecord.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key was already used for a different request"
                );
            }

            if (existingRecord.getTransaction() == null) {
                throw new IllegalStateException("Idempotency record has no associated transaction");
            }

            return toTransferResponse(existingRecord.getTransaction());
        }

        IdempotencyRecord idempotencyRecord =
                idempotencyRecordRepository
                        .findByUserIdAndIdempotencyKey(
                                senderUser.getId(),
                                idempotencyKey
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Reserved idempotency record was not found"
                        ));

        idempotencyRecordRepository.save(idempotencyRecord);

        // Lock both wallet rows in deterministic order to reduce
        // deadlock risk when opposite-direction transfers overlap.
        UUID firstId = Comparator.<UUID>naturalOrder()
                .compare(senderWalletId, receiverWalletId) < 0
                ? senderWalletId
                : receiverWalletId;

        UUID secondId = firstId.equals(senderWalletId)
                ? receiverWalletId
                : senderWalletId;

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Wallet not found"));

        Wallet secondWallet = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Wallet not found"));

        Wallet senderWallet = senderWalletId.equals(firstId)
                ? firstWallet
                : secondWallet;

        Wallet receiverWallet = receiverWalletId.equals(firstId)
                ? firstWallet
                : secondWallet;

        // Never trust a wallet ID alone to establish ownership.
        if (!senderWallet.getUser().getId().equals(senderUser.getId())) {
            throw new java.util.NoSuchElementException("Wallet not found");
        }

        validateWallets(senderWallet, receiverWallet);

        senderWallet.debit(amount);
        receiverWallet.credit(amount);

        String reference = "TRF-" + UUID.randomUUID();

        LedgerTransaction transaction = new LedgerTransaction(
                reference,
                LedgerTransactionType.WALLET_TRANSFER,
                "Wallet-to-wallet transfer"
        );

        transaction.addEntry(new LedgerEntry(
                senderWallet,
                LedgerEntryType.DEBIT,
                amount,
                senderWallet.getCurrency()
        ));

        transaction.addEntry(new LedgerEntry(
                receiverWallet,
                LedgerEntryType.CREDIT,
                amount,
                receiverWallet.getCurrency()
        ));

        ledgerValidationService.validateBalanced(transaction);
        validateTransferEntries(transaction, senderWallet, receiverWallet, amount);

        transaction.markPosted();

        // Ledger entries are persisted through the transaction's
        // CascadeType.PERSIST relationship.
        ledgerTransactionRepository.save(transaction);

        idempotencyRecord.associateTransaction(transaction);

        return new TransferResponse(
                transaction.getReference(),
                transaction.getStatus().name(),
                amount,
                senderWallet.getCurrency(),
                transaction.getCreatedAt()
        );

    }

    private void validateRequest(UUID senderWalletId, UUID receiverWalletId, BigDecimal amount) {
        if (senderWalletId == null || receiverWalletId == null) {
            throw new IllegalArgumentException("Wallet IDs are required");
        }

        if (senderWalletId.equals(receiverWalletId)) {
            throw new IllegalArgumentException("Sender and receiver wallets must be different");
        }

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be greater than zero");
        }

        if (amount.scale() > 4) {
            throw new IllegalArgumentException("Transfer amount cannot have more than 4 decimal places");
        }
    }

    private void validateWallets(
            Wallet senderWallet,
            Wallet receiverWallet
    ) {
        if (senderWallet.getStatus() != WalletStatus.ACTIVE
                || receiverWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Both wallets must be active"
            );
        }

        if (!senderWallet.getCurrency()
                .equals(receiverWallet.getCurrency())) {
            throw new IllegalArgumentException(
                    "Wallet currencies must match"
            );
        }
    }

    private void validateTransferEntries(
            LedgerTransaction transaction,
            Wallet sender,
            Wallet receiver,
            BigDecimal amount
    ) {
        if (transaction.getEntries().size() != 2) {
            throw new IllegalStateException("A wallet transfer must contain exactly two entries");
        }

        LedgerEntry debit = transaction.getEntries().stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Transfer debit entry is missing"
                ));

        LedgerEntry credit = transaction.getEntries().stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Transfer credit entry is missing"
                ));

        if (!debit.getWallet().getId().equals(sender.getId())
                || !credit.getWallet().getId().equals(receiver.getId())
                || debit.getAmount().compareTo(amount) != 0
                || credit.getAmount().compareTo(amount) != 0
                || !debit.getCurrency().equals(credit.getCurrency())) {
            throw new IllegalStateException(
                    "Transfer ledger entries do not match the transfer"
            );
        }
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String normalizedKey = key.trim();

        if (normalizedKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 255 characters");
        }

        return normalizedKey;
    }

    private TransferResponse toTransferResponse(LedgerTransaction transaction) {
        LedgerEntry debit = transaction.getEntries().stream()
                .filter(entry -> entry.getEntryType() == LedgerEntryType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Transfer debit entry is missing"
                ));

        return new TransferResponse(
                transaction.getReference(),
                transaction.getStatus().name(),
                debit.getAmount(),
                debit.getCurrency(),
                transaction.getCreatedAt()
        );
    }

}
