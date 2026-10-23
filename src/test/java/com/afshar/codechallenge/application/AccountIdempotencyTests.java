package com.afshar.codechallenge.application;

import com.afshar.codechallenge.support.AccountIntegrationTestSupport;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AccountIdempotencyTests extends AccountIntegrationTestSupport {

    @Test
    void repeatingACreditDoesNotChangeBalancesAgain() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        service.credit("alice", 100, "same-request");

        assertThatThrownBy(() -> service.credit("alice", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThatThrownBy(() -> service.credit("alice", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1100);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void repeatingADebitDoesNotChangeBalancesAgain() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        service.debit("alice", 100, "same-request");

        assertThatThrownBy(() -> service.debit("alice", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThatThrownBy(() -> service.debit("alice", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThat(service.getBalance("alice")).isEqualTo(900);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void repeatingATransferDoesNotChangeBalancesAgain() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);
        service.transfer("alice", "bob", 100, "same-request");

        assertThatThrownBy(() -> service.transfer("alice", "bob", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThatThrownBy(() -> service.transfer("alice", "bob", 100, "same-request"))
                .isInstanceOf(TransactionDuplicationException.class);

        assertThat(service.getBalance("alice")).isEqualTo(900);
        assertThat(service.getBalance("bob")).isEqualTo(600);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void insufficientDebitRollsBackAndItsIdCanBeRetried() {
        service.createAccount("alice", 1000);

        assertThatThrownBy(() -> service.debit("alice", 1200, "debit"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.existsById("debit")).isFalse();

        service.credit("alice", 200, "funding");
        service.debit("alice", 1200, "debit");

        assertThat(service.getBalance("alice")).isZero();
        assertThat(transactions.count()).isEqualTo(2);
    }

    @Test
    void missingTransferDestinationRollsBackAndCanBeRetried() {
        service.createAccount("alice", 1000);

        assertThatThrownBy(() -> service.transfer("alice", "bob", 300, "payment"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.existsById("payment")).isFalse();

        service.createAccount("bob", 500);
        service.transfer("alice", "bob", 300, "payment");

        assertThat(service.getBalance("alice")).isEqualTo(700);
        assertThat(service.getBalance("bob")).isEqualTo(800);
    }

    @Test
    void aFailedCreditCanBeRetriedAfterTheMissingAccountIsCreated() {
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.credit("missing", 100, "retry-request"))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(transactions.count()).isZero();
        assertThat(service.getBalance("bob")).isEqualTo(500);

        service.createAccount("missing", 100);
        service.credit("missing", 100, "retry-request");

        assertThat(service.getBalance("missing")).isEqualTo(200);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void aFailedDebitCanBeRetriedAfterTheMissingAccountIsCreated() {
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.debit("missing", 100, "retry-request"))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(transactions.count()).isZero();
        assertThat(service.getBalance("bob")).isEqualTo(500);

        service.createAccount("missing", 100);
        service.debit("missing", 100, "retry-request");

        assertThat(service.getBalance("missing")).isZero();
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void aFailedTransferCanBeRetriedAfterTheMissingAccountIsCreated() {
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer("missing", "bob", 100, "retry-request"))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(transactions.count()).isZero();
        assertThat(service.getBalance("bob")).isEqualTo(500);

        service.createAccount("missing", 100);
        service.transfer("missing", "bob", 100, "retry-request");

        assertThat(service.getBalance("missing")).isZero();
        assertThat(service.getBalance("bob")).isEqualTo(600);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void anOverflowingCreditRollsBackAndCanBeRetried() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", Long.MAX_VALUE);

        assertThatThrownBy(() -> service.credit("bob", 1, "retry-request"))
                .isInstanceOf(ArithmeticException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactions.count()).isZero();

        service.debit("bob", 500, "make-room");
        service.credit("bob", 1, "retry-request");

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE - 499);
        assertThat(transactions.count()).isEqualTo(2);
    }

    @Test
    void anOverflowingTransferRollsBackAndCanBeRetried() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", Long.MAX_VALUE);

        assertThatThrownBy(() -> service.transfer("alice", "bob", 300, "retry-request"))
                .isInstanceOf(ArithmeticException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactions.count()).isZero();

        service.debit("bob", 500, "make-room");
        service.transfer("alice", "bob", 300, "retry-request");

        assertThat(service.getBalance("alice")).isEqualTo(700);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE - 200);
        assertThat(transactions.count()).isEqualTo(2);
    }

}

