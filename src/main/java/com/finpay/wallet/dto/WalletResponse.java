
package com.finpay.wallet.dto;

import com.finpay.wallet.entity.Wallet;
import com.finpay.wallet.entity.WalletStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WalletResponse(
    UUID id,
    String currency,
    BigDecimal balance,
    WalletStatus status,
    OffsetDateTime createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
            wallet.getId(),
            wallet.getCurrency(),
            wallet.getBalance(),
            wallet.getStatus(),
            wallet.getCreatedAt()
        );
    }
}