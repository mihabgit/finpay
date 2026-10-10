
package com.finpay.wallet.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransferResponse(
    String reference,
    String status,
    BigDecimal amount,
    String currency,
    OffsetDateTime createdAt
) {}