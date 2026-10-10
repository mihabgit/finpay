
package com.finpay.wallet.controller;

import com.finpay.wallet.dto.CreateWalletRequest;
import com.finpay.wallet.dto.WalletResponse;
import com.finpay.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse createWallet(Authentication authentication, @Valid @RequestBody CreateWalletRequest request) {
        return walletService.createWallet(authentication.getName(), request);
    }

    @GetMapping
    public List<WalletResponse> getMyWallets(Authentication authentication) {
        return walletService.getMyWallets(authentication.getName());
    }

    @GetMapping("/{walletId}")
    public WalletResponse getMyWallet(Authentication authentication, @PathVariable UUID walletId) {
        return walletService.getMyWallet(authentication.getName(), walletId);
    }
}