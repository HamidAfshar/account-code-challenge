package com.afshar.codechallenge.domain;


import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity(name = "Account")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountEntity {

    @Id
    private String accountId;

    @Column(nullable = false)
    private long balance;

    private static void requirePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public AccountEntity snapshot() {
        return new AccountEntity(accountId, balance);
    }

    public void credit(long amount) {
        requirePositiveAmount(amount);
        balance = Math.addExact(balance, amount);
    }

    public void debit(long amount) {
        requirePositiveAmount(amount);
        if (balance < amount) throw new InsufficientFundsException("Insufficient funds");

        balance = Math.subtractExact(balance, amount);
    }

}
