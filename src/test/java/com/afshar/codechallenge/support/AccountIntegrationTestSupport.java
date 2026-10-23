package com.afshar.codechallenge.support;

import com.afshar.codechallenge.application.AccountService;
import com.afshar.codechallenge.infra.AccountRepository;
import com.afshar.codechallenge.infra.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:balance-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
public abstract class AccountIntegrationTestSupport {

    @Autowired protected AccountService service;
    @Autowired protected AccountRepository accounts;
    @Autowired protected TransactionRepository transactions;
    @Autowired protected MockMvc mvc;
    @Autowired protected PlatformTransactionManager transactionManager;

    @BeforeEach
    protected void cleanDatabase() {
        transactions.deleteAll();
        accounts.deleteAll();
    }
}

