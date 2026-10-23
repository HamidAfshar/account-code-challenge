package com.afshar.codechallenge.application;

import com.afshar.codechallenge.domain.AccountEntity;
import com.afshar.codechallenge.domain.TransactionEntity;
import com.afshar.codechallenge.domain.exception.AccountAlreadyExistException;
import com.afshar.codechallenge.domain.exception.TransactionDuplicationException;
import com.afshar.codechallenge.infra.AccountRepository;
import com.afshar.codechallenge.infra.EntityCreator;
import com.afshar.codechallenge.infra.TransactionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.CHECK;
import static org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccountServiceTests {

    @Mock AccountRepository accounts;
    @Mock TransactionRepository transactions;
    @Mock EntityCreator entityCreator;

    DefaultAccountService service;

    @BeforeEach
    void setUp() {
        service = new DefaultAccountService(accounts, transactions, entityCreator);
    }

    @Test
    void aNestedUniqueViolationMeansTheAccountAlreadyExists() {
        var uniqueViolation = new ConstraintViolationException(
                "Account ID is already taken", new SQLException("Duplicate key"), UNIQUE, "account_pkey");
        var persistenceFailure = new DataIntegrityViolationException(
                "Could not open account", new IllegalStateException("Flush failed", uniqueViolation));
        when(entityCreator.persistAndFlush(any(AccountEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.createAccount("alice", 1000))
                .isInstanceOf(AccountAlreadyExistException.class)
                .hasMessage("Account already exists")
                .hasCause(persistenceFailure);

        verifyNoInteractions(accounts, transactions);
    }

    @Test
    void anAccountCheckConstraintFailureIsNotReportedAsADuplicateAccount() {
        var checkViolation = new ConstraintViolationException(
                "Opening balance violates a constraint", new SQLException("Check failed"), CHECK, "account_balance_check");
        var persistenceFailure = new DataIntegrityViolationException("Could not open account", checkViolation);
        when(entityCreator.persistAndFlush(any(AccountEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.createAccount("alice", 1000))
                .isSameAs(persistenceFailure);

        verifyNoInteractions(accounts, transactions);
    }

    @Test
    void anAccountPersistenceFailureWithoutACauseIsPassedBackUnchanged() {
        var persistenceFailure = new DataIntegrityViolationException("Could not open account");
        when(entityCreator.persistAndFlush(any(AccountEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.createAccount("alice", 1000))
                .isSameAs(persistenceFailure);

        verifyNoInteractions(accounts, transactions);
    }

    @Test
    void aNestedUniqueViolationRejectsTheDepositBeforeTheAccountIsTouched() {
        var uniqueViolation = new ConstraintViolationException(
                "Transaction ID is already taken", new SQLException("Duplicate key"), UNIQUE, "transactions_pkey");
        var persistenceFailure = new DataIntegrityViolationException(
                "Could not record deposit", new IllegalStateException("Flush failed", uniqueViolation));
        when(entityCreator.persistAndFlush(any(TransactionEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.credit("alice", 100, "salary"))
                .isInstanceOf(TransactionDuplicationException.class)
                .hasMessage("salary")
                .hasCause(persistenceFailure);

        verifyNoInteractions(accounts);
    }

    @Test
    void aTransactionCheckConstraintFailureIsNotReportedAsADuplicateDeposit() {
        var checkViolation = new ConstraintViolationException(
                "Transaction violates a constraint", new SQLException("Check failed"), CHECK, "transaction_amount_check");
        var persistenceFailure = new DataIntegrityViolationException("Could not record deposit", checkViolation);
        when(entityCreator.persistAndFlush(any(TransactionEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.credit("alice", 100, "salary"))
                .isSameAs(persistenceFailure);

        verifyNoInteractions(accounts);
    }

    @Test
    void aDepositPersistenceFailureWithoutACauseLeavesTheAccountUntouched() {
        var persistenceFailure = new DataIntegrityViolationException("Could not record deposit");
        when(entityCreator.persistAndFlush(any(TransactionEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.credit("alice", 100, "salary"))
                .isSameAs(persistenceFailure);

        verifyNoInteractions(accounts);
    }
}
