package com.afshar.codechallenge.api;

import com.afshar.codechallenge.api.dto.AccountResponse;
import com.afshar.codechallenge.api.dto.CreateAccountRequest;
import com.afshar.codechallenge.api.dto.CreditRequest;
import com.afshar.codechallenge.api.dto.DebitRequest;
import com.afshar.codechallenge.application.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public String createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request.accountId(), request.initAmount());
    }

    @GetMapping("/{accountId}/balance")
    public AccountResponse getBalance(@PathVariable @NotBlank @Size(max = 255) String accountId) {
        return new AccountResponse(accountId, accountService.getBalance(accountId));
    }

    @PostMapping("/{accountId}/credit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void credit(@PathVariable @NotBlank @Size(max = 255) String accountId,
                       @Valid @RequestBody CreditRequest request) {
        accountService.credit(accountId, request.amount(), request.transactionId());
    }

    @PostMapping("/{accountId}/debit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void debit(@PathVariable @NotBlank @Size(max = 255) String accountId,
                      @Valid @RequestBody DebitRequest request) {
        accountService.debit(accountId, request.amount(), request.transactionId());
    }
}
