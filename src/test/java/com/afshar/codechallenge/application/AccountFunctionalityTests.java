package com.afshar.codechallenge.application;

import com.afshar.codechallenge.support.AccountIntegrationTestSupport;
import com.afshar.codechallenge.domain.TransactionType;
import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.AccountNotFoundException;
import com.afshar.codechallenge.domain.exception.InsufficientFundsException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;

class AccountFunctionalityTests extends AccountIntegrationTestSupport {

    @Test
    void negativeInitialBalanceIsRejected() {
        assertThatThrownBy(() -> service.createAccount("alice", -1))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(accounts.existsById("alice")).isFalse();
    }

    @Test
    void sameAccountTransferIsRejectedWithoutARecord() {
        service.createAccount("alice", 1000);

        assertThatThrownBy(() -> service.transfer("alice", "alice", 100, "payment"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void insufficientTransferRollsBackBothBalances() {
        service.createAccount("alice", 100);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer("alice", "bob", 300, "payment"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(service.getBalance("alice")).isEqualTo(100);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void duplicateCreationCannotResetBalance() {
        service.createAccount("alice", 1000);
        service.credit("alice", 100, "deposit");

        assertThatThrownBy(() -> service.createAccount("alice", 0))
                .isInstanceOf(AccountAlreadyExistException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1100);
    }

    @Test
    void oversizedTransactionIdIsRejectedBeforePersistence() {
        service.createAccount("alice", 1000);

        assertThatThrownBy(() -> service.credit("alice", 100, "x".repeat(256)))
                .isInstanceOf(ConstraintViolationException.class)
                .isNotInstanceOf(TransactionDuplicationException.class);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void oversizedAccountIdIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.createAccount("x".repeat(256), 1000))
                .isInstanceOf(ConstraintViolationException.class)
                .isNotInstanceOf(AccountAlreadyExistException.class);

        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void maximumLengthIdentifiersAreAcceptedByServiceOperations() {
        String accountId = "a".repeat(255);
        String destinationId = "b".repeat(255);
        service.createAccount(accountId, 1000);
        service.createAccount(destinationId, 500);
        service.credit(accountId, 100, "c".repeat(255));
        service.debit(accountId, 50, "d".repeat(255));
        service.transfer(accountId, destinationId, 25, "t".repeat(255));

        assertThat(service.getBalance(accountId)).isEqualTo(1025);
        assertThat(service.getBalance(destinationId)).isEqualTo(525);
        assertThat(transactions.count()).isEqualTo(3);
    }

    @Test
    void aNewAccountStartsWithItsOpeningBalance() {
        service.createAccount("alice", 1000);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(accounts.count()).isOne();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void anAccountCanBeOpenedWithoutAnInitialDeposit() {
        service.createAccount("alice", 0);

        assertThat(service.getBalance("alice")).isZero();
    }

    @Test
    void aDepositIncreasesTheBalanceAndRecordsTheTransaction() {
        service.createAccount("alice", 1000);

        service.credit("alice", 500, "credit-request");

        assertThat(service.getBalance("alice")).isEqualTo(1500);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("credit-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(recorded.getAmount()).isEqualTo(500);
        assertThat(recorded.getSourceAccountId()).isNull();
        assertThat(recorded.getDestinationAccountId()).isEqualTo("alice");
    }

    @Test
    void aWithdrawalDecreasesTheBalanceAndRecordsTheTransaction() {
        service.createAccount("alice", 1000);

        service.debit("alice", 500, "debit-request");

        assertThat(service.getBalance("alice")).isEqualTo(500);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("debit-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(recorded.getAmount()).isEqualTo(500);
        assertThat(recorded.getSourceAccountId()).isEqualTo("alice");
        assertThat(recorded.getDestinationAccountId()).isNull();
    }

    @Test
    void aTransferMovesMoneyAndRecordsBothAccounts() {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 200);

        service.transfer("alice", "bob", 500, "transfer-request");

        assertThat(service.getBalance("alice")).isEqualTo(500);
        assertThat(service.getBalance("bob")).isEqualTo(700);
        assertThat(transactions.count()).isOne();
        var recorded = transactions.findById("transfer-request").orElseThrow();
        assertThat(recorded.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(recorded.getAmount()).isEqualTo(500);
        assertThat(recorded.getSourceAccountId()).isEqualTo("alice");
        assertThat(recorded.getDestinationAccountId()).isEqualTo("bob");
    }

    @Test
    void askingForAnUnknownAccountsBalanceFails() {
        assertThatThrownBy(() -> service.getBalance("missing"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aDepositRejectsNonPositiveAmounts(long amount) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.credit("alice", amount, "deposit"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aWithdrawalRejectsNonPositiveAmounts(long amount) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.debit("alice", amount, "withdrawal"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aTransferRejectsNonPositiveAmounts(long amount) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer("alice", "bob", amount, "payment"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void openingAnAccountRequiresAValidId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.createAccount(invalidId, 0))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void readingABalanceRequiresAValidId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.getBalance(invalidId))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aDepositRequiresAValidAccountId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.credit(invalidId, 1, "deposit"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aWithdrawalRequiresAValidAccountId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.debit(invalidId, 1, "withdrawal"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aTransferRequiresAValidSourceId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer(invalidId, "bob", 1, "payment"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aTransferRequiresAValidDestinationId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer("alice", invalidId, 1, "payment"))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aDepositRequiresAValidTransactionId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.credit("alice", 1, invalidId))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aWithdrawalRequiresAValidTransactionId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.debit("alice", 1, invalidId))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    void aTransferRequiresAValidTransactionId(String invalidId) {
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        assertThatThrownBy(() -> service.transfer("alice", "bob", 1, invalidId))
                .isInstanceOf(ConstraintViolationException.class);

        assertThat(accounts.count()).isEqualTo(2);
        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    private static java.util.stream.Stream<String> invalidIdentifiers() {
        return java.util.stream.Stream.of("", " ", "x".repeat(256));
    }
}

