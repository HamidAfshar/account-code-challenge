package com.afshar.codechallenge.api;

import com.afshar.codechallenge.api.dto.TransferRequest;
import com.afshar.codechallenge.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final AccountService accountService;

    public TransferController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@Valid @RequestBody TransferRequest request) {
        accountService.transfer(request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.transactionId());
    }
}
