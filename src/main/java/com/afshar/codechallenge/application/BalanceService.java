package com.afshar.codechallenge.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public interface BalanceService {

    void credit(@NotBlank @Size(max = 255) String accountId, @Positive long amount, @NotBlank @Size(max = 255) String transactionId);

    void debit(@NotBlank @Size(max = 255) String accountId, @Positive long amount, @NotBlank @Size(max = 255) String transactionId);

    void transfer(@NotBlank @Size(max = 255) String sourceAccountId, @NotBlank @Size(max = 255) String destinationAccountId, @Positive long amount, @NotBlank @Size(max = 255) String transactionId);

    long getBalance(@NotBlank @Size(max = 255) String accountId);
}
