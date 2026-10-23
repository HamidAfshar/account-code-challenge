package com.afshar.codechallenge.application;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public interface AccountService extends BalanceService {

    String createAccount(@NotBlank @Size(max = 255) String accountId, @PositiveOrZero long initAmount);
}
