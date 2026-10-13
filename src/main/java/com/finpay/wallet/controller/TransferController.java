
package com.finpay.wallet.controller;

import com.finpay.wallet.dto.TransferRequest;
import com.finpay.wallet.dto.TransferResponse;
import com.finpay.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        return transferService.transfer(
                authentication.getName(),
                request.senderWalletId(),
                request.receiverWalletId(),
                request.amount(),
                idempotencyKey
        );
    }
}