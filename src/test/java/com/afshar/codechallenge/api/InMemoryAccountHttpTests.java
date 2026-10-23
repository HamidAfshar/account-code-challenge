package com.afshar.codechallenge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("inMemory")
class InMemoryAccountHttpTests {
    @Autowired MockMvc mvc;

    @Test
    void existingEndpointsWorkWithoutDatabaseInfrastructure() throws Exception {
        for (String account : new String[]{"alice", "bob"}) {
            mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":"%s","initAmount":100}
                                    """.formatted(account)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andExpect(content().string(account));
        }
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"alice","initAmount":0}
                                """))
                .andExpect(status().isConflict());
        mvc.perform(post("/accounts/alice/credit").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10,"transactionId":"credit"}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(post("/accounts/alice/debit").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":5,"transactionId":"debit"}
                                """))
                .andExpect(status().isNoContent());
        String transfer = """
                {"sourceAccountId":"alice","destinationAccountId":"bob",
                 "amount":5,"transactionId":"transfer"}
                """;
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(transfer))
                .andExpect(status().isNoContent());
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(transfer))
                .andExpect(status().isConflict());
        mvc.perform(post("/accounts/alice/credit").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0,"transactionId":"invalid"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/accounts/alice/balance"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(100));
        mvc.perform(get("/accounts/bob/balance"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(105));
        mvc.perform(get("/accounts/missing/balance"))
                .andExpect(status().isNotFound());
    }
}
