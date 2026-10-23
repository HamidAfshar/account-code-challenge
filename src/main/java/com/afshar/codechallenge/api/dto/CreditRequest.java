package com.afshar.codechallenge.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreditRequest(@NotNull @Positive Long amount, @NotBlank @Size(max = 255) String transactionId) {
}
