package com.afshar.codechallenge.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEntityTests {

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void anInvalidDepositLeavesTheBalanceUnchanged(long amount) {
        AccountEntity account = AccountEntity.builder().accountId("alice").balance(100).build();

        assertThatThrownBy(() -> account.credit(amount))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(account.getBalance()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void anInvalidWithdrawalLeavesTheBalanceUnchanged(long amount) {
        AccountEntity account = AccountEntity.builder().accountId("alice").balance(100).build();

        assertThatThrownBy(() -> account.debit(amount))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(account.getBalance()).isEqualTo(100);
    }
}
