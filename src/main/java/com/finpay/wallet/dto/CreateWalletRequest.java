
package com.finpay.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWalletRequest(
    @NotBlank
    @Pattern(regexp = "BDT|MYR|USD|SGD|EUR|GBP")
    String currency
) {}