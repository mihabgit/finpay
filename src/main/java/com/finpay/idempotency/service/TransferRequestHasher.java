
package com.finpay.idempotency.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class TransferRequestHasher {

    public String hash(UUID senderWalletId, UUID receiverWalletId, BigDecimal amount) {
        String canonicalRequest = String.join(
                "|",
                "wallet-transfer-v1",
                senderWalletId.toString(),
                receiverWalletId.toString(),
                amount.stripTrailingZeros().toPlainString()
        );

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
