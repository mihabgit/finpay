
package com.finpay.wallet.controller;

import com.finpay.identity.entity.User;
import com.finpay.identity.repository.UserRepository;
import com.finpay.ledger.repository.LedgerEntryRepository;
import com.finpay.ledger.repository.LedgerTransactionRepository;
import com.finpay.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WalletControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerEntryRepository  ledgerEntryRepository;

    @Autowired
    LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        // Clear database state to ensure isolation between test runs
        ledgerEntryRepository.deleteAll();
        ledgerTransactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        String email = "wallet-test@example.com";

        // Register the user through the actual API.
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "wallet-test@example.com",
                        "password": "password123",
                        "firstName": "Wallet",
                        "lastName": "Test"
                    }
                    """))
            .andExpect(status().isCreated());

        // Login to obtain a real JWT.
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "wallet-test@example.com",
                        "password": "password123"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        accessToken = com.jayway.jsonpath.JsonPath
            .read(response, "$.accessToken");
    }

    @Test
    void createWallet_shouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currency": "MYR"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.currency").value("MYR"))
            .andExpect(jsonPath("$.balance").value(0))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createWallet_shouldRejectUnsupportedCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/wallets")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currency": "JPY"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createWallet_shouldRejectDuplicateCurrency() throws Exception {
        String request = """
            {"currency": "MYR"}
            """;

        mockMvc.perform(post("/api/v1/wallets")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallets")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest());
    }

    @Test
    void walletEndpoints_shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/wallets"))
            .andExpect(status().isForbidden());
    }
}