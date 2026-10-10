
package com.finpay.wallet.service;

import com.finpay.identity.entity.User;
import com.finpay.identity.repository.UserRepository;
import com.finpay.wallet.dto.CreateWalletRequest;
import com.finpay.wallet.dto.WalletResponse;
import com.finpay.wallet.entity.Wallet;
import com.finpay.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WalletService {

    private static final java.util.Set<String> SUPPORTED_CURRENCIES =
        java.util.Set.of("BDT", "MYR", "USD", "SGD", "EUR", "GBP");

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public WalletService(WalletRepository walletRepository, UserRepository userRepository) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public WalletResponse createWallet(String authenticatedEmail, CreateWalletRequest request) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        String currency = request.currency().trim().toUpperCase(Locale.ROOT);

        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency");
        }

        if (walletRepository.existsByUserIdAndCurrencyIgnoreCase(user.getId(), currency)) {
            throw new IllegalArgumentException("A wallet for this currency already exists");
        }

        Wallet wallet = new Wallet(user, currency);
        return WalletResponse.from(walletRepository.save(wallet));
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> getMyWallets(String authenticatedEmail) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        return walletRepository.findAllByUserId(user.getId())
            .stream()
            .map(WalletResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public WalletResponse getMyWallet(String authenticatedEmail, UUID walletId) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        Wallet wallet = walletRepository.findByIdAndUserId(walletId, user.getId())
            .orElseThrow(() -> new java.util.NoSuchElementException("Wallet not found"));

        return WalletResponse.from(wallet);
    }
}