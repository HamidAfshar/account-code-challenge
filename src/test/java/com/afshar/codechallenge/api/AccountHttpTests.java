package com.afshar.codechallenge.api;

import com.afshar.codechallenge.support.AccountIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountHttpTests extends AccountIntegrationTestSupport {

    @Test
    void openingAnExistingAccountReturnsAConflictWithoutResettingItsBalance() throws Exception {
        service.createAccount("alice", 1000);
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"alice\",\"initAmount\":0}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Account already exists"));
        assertThat(service.getBalance("alice")).isEqualTo(1000);
    }

    @Test
    void repeatingADepositReturnsAConflictWithoutAddingMoneyAgain() throws Exception {
        service.createAccount("alice", 1000);
        String body = "{\"amount\":100,\"transactionId\":\"duplicate\"}";
        mvc.perform(post("/accounts/alice/credit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        expectProblem("/accounts/alice/credit", body, 409);
        mvc.perform(post("/accounts/alice/credit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("duplicate"));
        assertThat(service.getBalance("alice")).isEqualTo(1100);
        assertThat(transactions.count()).isOne();
    }

    @Test
    void requestsAcceptIdentifiersAtTheMaximumLength() throws Exception {
        String accountId = "a".repeat(255);
        String destinationId = "b".repeat(255);
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","initAmount":1000}
                                """.formatted(accountId)))
                .andExpect(status().isCreated());
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","initAmount":1000}
                                """.formatted(destinationId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/accounts/{accountId}/credit", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100,"transactionId":"%s"}
                                """.formatted("c".repeat(255))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/accounts/{accountId}/debit", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100,"transactionId":"%s"}
                                """.formatted("d".repeat(255))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s",
                                 "amount":25,"transactionId":"%s"}
                                """.formatted(accountId, destinationId, "t".repeat(255))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/accounts/{accountId}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(975));
        mvc.perform(get("/accounts/{accountId}/balance", destinationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1025));
        assertThat(transactions.count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"amount\":0,\"transactionId\":\"t\"}",
            "{\"amount\":-1,\"transactionId\":\"t\"}",
            "{\"amount\":1,\"transactionId\":\" \"}"
    })
    void anInvalidCreditRequestReturnsBadRequest(String body) throws Exception {
        service.createAccount("alice", 1000);

        expectProblem("/accounts/alice/credit", body, 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"amount\":0,\"transactionId\":\"t\"}",
            "{\"amount\":-1,\"transactionId\":\"t\"}",
            "{\"amount\":1,\"transactionId\":\" \"}"
    })
    void anInvalidDebitRequestReturnsBadRequest(String body) throws Exception {
        service.createAccount("alice", 1000);

        expectProblem("/accounts/alice/debit", body, 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void accountsCanBeOpenedFundedAndUsedToTransferMoney() throws Exception {
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"alice","initAmount":1000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("alice"));
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"bob","initAmount":500}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("bob"));

        mvc.perform(post("/accounts/alice/credit").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":500,"transactionId":"salary"}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(post("/accounts/alice/debit").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":700,"transactionId":"rent"}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"alice","destinationAccountId":"bob",
                                 "amount":300,"transactionId":"shared-dinner"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/accounts/alice/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("alice"))
                .andExpect(jsonPath("$.balance").value(500));
        mvc.perform(get("/accounts/bob/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("bob"))
                .andExpect(jsonPath("$.balance").value(800));
        assertThat(transactions.count()).isEqualTo(3);
    }

    @Test
    void withdrawingMoreThanTheBalanceReturnsAConflict() throws Exception {
        service.createAccount("alice", 1000);

        expectProblem("/accounts/alice/debit", """
                {"amount":1200,"transactionId":"rent"}
                """, 409);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void depositingIntoAnUnknownAccountReturnsNotFound() throws Exception {
        expectProblem("/accounts/missing/credit", """
                {"amount":1,"transactionId":"deposit"}
                """, 404);

        assertThat(accounts.count()).isZero();
        assertThat(transactions.count()).isZero();
    }

    @Test
    void readingAnUnknownAccountsBalanceReturnsNotFound() throws Exception {
        mvc.perform(get("/accounts/missing/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transferringToTheSameAccountReturnsBadRequest() throws Exception {
        service.createAccount("alice", 1000);

        expectProblem("/transfers", """
                {"sourceAccountId":"alice","destinationAccountId":"alice",
                 "amount":1,"transactionId":"self-transfer"}
                """, 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aDepositThatWouldOverflowReturnsAConflict() throws Exception {
        service.createAccount("alice", Long.MAX_VALUE);

        expectProblem("/accounts/alice/credit", """
                {"amount":1,"transactionId":"overflow-deposit"}
                """, 409);

        assertThat(service.getBalance("alice")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aTransferThatWouldOverflowReturnsAConflictAndKeepsBothBalances() throws Exception {
        service.createAccount("alice", 1000);
        service.createAccount("bob", Long.MAX_VALUE);

        expectProblem("/transfers", """
                {"sourceAccountId":"alice","destinationAccountId":"bob",
                 "amount":1,"transactionId":"overflow-transfer"}
                """, 409);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(Long.MAX_VALUE);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void openingAnAccountWithAnOversizedIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);

        expectProblem("/accounts", """
                {"accountId":"%s","initAmount":0}
                """.formatted(oversizedId), 400);

        assertThat(accounts.count()).isZero();
    }

    @Test
    void readingABalanceWithAnOversizedIdReturnsBadRequest() throws Exception {
        mvc.perform(get("/accounts/{accountId}/balance", "x".repeat(256)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCreditWithAnOversizedAccountIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);

        expectProblem("/accounts/" + oversizedId + "/credit", """
                {"amount":1,"transactionId":"deposit"}
                """, 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aCreditWithAnOversizedTransactionIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);

        expectProblem("/accounts/alice/credit", """
                {"amount":1,"transactionId":"%s"}
                """.formatted(oversizedId), 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aDebitWithAnOversizedAccountIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);

        expectProblem("/accounts/" + oversizedId + "/debit", """
                {"amount":1,"transactionId":"deposit"}
                """, 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aDebitWithAnOversizedTransactionIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);

        expectProblem("/accounts/alice/debit", """
                {"amount":1,"transactionId":"%s"}
                """.formatted(oversizedId), 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aTransferWithAnOversizedSourceIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        expectProblem("/transfers", """
                {"sourceAccountId":"%s","destinationAccountId":"bob",
                 "amount":1,"transactionId":"payment"}
                """.formatted(oversizedId), 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aTransferWithAnOversizedDestinationIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        expectProblem("/transfers", """
                {"sourceAccountId":"alice","destinationAccountId":"%s",
                 "amount":1,"transactionId":"payment"}
                """.formatted(oversizedId), 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    @Test
    void aTransferWithAnOversizedTransactionIdReturnsBadRequest() throws Exception {
        String oversizedId = "x".repeat(256);
        service.createAccount("alice", 1000);
        service.createAccount("bob", 500);

        expectProblem("/transfers", """
                {"sourceAccountId":"alice","destinationAccountId":"bob",
                 "amount":1,"transactionId":"%s"}
                """.formatted(oversizedId), 400);

        assertThat(service.getBalance("alice")).isEqualTo(1000);
        assertThat(service.getBalance("bob")).isEqualTo(500);
        assertThat(transactions.count()).isZero();
    }

    private void expectProblem(String path, String body, int expectedStatus) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
