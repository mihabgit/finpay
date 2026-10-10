
package com.finpay.wallet.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpay.ledger.entity.LedgerTransactionStatus;
import com.finpay.ledger.repository.LedgerEntryRepository;
import com.finpay.ledger.repository.LedgerTransactionRepository;
import com.finpay.wallet.entity.Wallet;
import com.finpay.wallet.repository.WalletRepository;
import com.finpay.wallet.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("finpay_transfer_test")
            .withUsername("finpay")
            .withPassword("finpay");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferService transferService;

    private String senderToken;
    private String receiverToken;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private String senderEmail;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString();

        senderEmail = "sender-" + suffix + "@example.com";

        senderToken = registerAndLogin(
            "sender-" + suffix + "@example.com", "Sender"
        );

        receiverToken = registerAndLogin(
            "receiver-" + suffix + "@example.com", "Receiver"
        );

        senderWalletId = createWallet(senderToken, "MYR");
        receiverWalletId = createWallet(receiverToken, "MYR");

        // Test-only funding. There is deliberately no public
        // wallet balance-setting endpoint in the application.
        setBalance(senderWalletId, "500.0000");
        setBalance(receiverWalletId, "50.0000");
    }

    @Test
    void transfer_shouldUpdateBalancesAndPersistBalancedLedger() throws Exception {

        int transactionsBefore =
            ledgerTransactionRepository.findAll().size();

        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, receiverWalletId, "100.0000"
                )))
            .andExpect(status().isCreated());

        assertBalance(senderWalletId, "400.0000");
        assertBalance(receiverWalletId, "150.0000");

        var transactions = ledgerTransactionRepository.findAll();
        assertThat(transactions).hasSize(transactionsBefore + 1);

        var transaction = transactions.stream()
                .filter(t -> t.getTransactionType().name().equals("WALLET_TRANSFER"))
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(transaction.getStatus())
                .isEqualTo(LedgerTransactionStatus.POSTED);

        // 2. Fetch the entries directly using the repository
        var entries = ledgerEntryRepository.findAllByTransactionId(transaction.getId());
        assertThat(entries).hasSize(2);

        // 3. NEW: Verify the specific transfer amount directly from the retrieved entries list
        assertThat(entries).anyMatch(e ->
                e.getWallet().getId().equals(senderWalletId)
                        && e.getAmount().compareTo(new BigDecimal("100.0000")) == 0);

        // 4. Calculate debits and credits from the retrieved list
        BigDecimal debits = entries.stream()
                .filter(e -> e.getEntryType().name().equals("DEBIT"))
                .map(e -> e.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal credits = entries.stream()
                .filter(e -> e.getEntryType().name().equals("CREDIT"))
                .map(e -> e.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(debits.compareTo(credits)).isZero();
        assertThat(debits.compareTo(new BigDecimal("100.0000"))).isZero();

        assertThat(entries)
                .anyMatch(e -> e.getWallet().getId().equals(senderWalletId)
                        && e.getEntryType().name().equals("DEBIT"));

        assertThat(entries)
                .anyMatch(e -> e.getWallet().getId().equals(receiverWalletId)
                        && e.getEntryType().name().equals("CREDIT"));
    }

    @Test
    void transfer_withInsufficientFunds_shouldNotChangeBalances() throws Exception {

        int transactionsBefore = ledgerTransactionRepository.findAll().size();

        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, receiverWalletId, "900.0000"
                )))
            .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");

        assertThat(ledgerTransactionRepository.findAll())
            .hasSize(transactionsBefore);
    }

    @Test
    void transfer_withAnotherUsersSenderWallet_shouldBeRejected() throws Exception {

        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    receiverWalletId, senderWalletId, "10.0000"
                )))
            .andExpect(status().isNotFound());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
    }

    @Test
    void transfer_betweenDifferentCurrencies_shouldBeRejected()
        throws Exception {

        UUID usdWalletId = createWallet(receiverToken, "USD");
        setBalance(usdWalletId, "100.0000");

        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, usdWalletId, "10.0000"
                )))
            .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(usdWalletId, "100.0000");
    }

    @Test
    void transfer_toSameWallet_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, senderWalletId, "10.0000"
                )))
            .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
    }

    @Test
    void transfer_withInvalidAmount_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, receiverWalletId, "-10.0000"
                )))
            .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
    }

    @Test
    void transfer_withoutAuthentication_shouldBeRejected()
        throws Exception {

        mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest(
                    senderWalletId, receiverWalletId, "10.0000"
                )))
            .andExpect(status().isForbidden());
    }

    private String registerAndLogin(String email, String name)
        throws Exception {

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "password123",
                      "firstName": "%s",
                      "lastName": "Test"
                    }
                    """.formatted(email, name)))
            .andExpect(status().isCreated());

        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "password123"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("accessToken").asText();
    }

    private UUID createWallet(String token, String currency)
        throws Exception {

        String response = mockMvc.perform(post("/api/v1/wallets")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currency": "%s"}
                    """.formatted(currency)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        return UUID.fromString(
            objectMapper.readTree(response).get("id").asText()
        );
    }

    private void setBalance(UUID walletId, String amount) {
        jdbcTemplate.update(
            "UPDATE wallets SET balance = ? WHERE id = ?",
            new BigDecimal(amount), walletId
        );
    }

    private void assertBalance(UUID walletId, String expected) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow();

        assertThat(wallet.getBalance().compareTo(
            new BigDecimal(expected))).isZero();
    }

    private String transferRequest(
        UUID senderId,
        UUID receiverId,
        String amount
    ) {
        return """
            {
              "senderWalletId": "%s",
              "receiverWalletId": "%s",
              "amount": %s
            }
            """.formatted(senderId, receiverId, amount);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }


    @Test
    void shouldRejectTransferWhenSenderWalletIsFrozen() throws Exception {
        jdbcTemplate.update(
                "UPDATE wallets SET status = 'FROZEN' WHERE id = ?",
                senderWalletId
        );

        assertTransferRejectedWithoutLedgerChanges(
                senderWalletId,
                receiverWalletId,
                "100.0000"
        );
    }

    @Test
    void shouldRejectTransferWhenReceiverWalletIsFrozen() throws Exception {
        jdbcTemplate.update(
                "UPDATE wallets SET status = 'FROZEN' WHERE id = ?",
                receiverWalletId
        );

        assertTransferRejectedWithoutLedgerChanges(
                senderWalletId,
                receiverWalletId,
                "100.0000"
        );
    }


    private void assertTransferRejectedWithoutLedgerChanges(
            UUID fromWalletId,
            UUID toWalletId,
            String amount
    ) throws Exception {

        BigDecimal senderBalanceBefore = getBalance(fromWalletId);
        BigDecimal receiverBalanceBefore = getBalance(toWalletId);
        long transactionsBefore = ledgerTransactionRepository.count();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + senderToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of(
                                        "senderWalletId", fromWalletId,
                                        "receiverWalletId", toWalletId,
                                        "amount", amount
                                )
                        )))
                .andExpect(status().isInternalServerError()); // need to change in future

        assertEquals(senderBalanceBefore, getBalance(fromWalletId));
        assertEquals(receiverBalanceBefore, getBalance(toWalletId));
        assertEquals(
                transactionsBefore,
                ledgerTransactionRepository.count()
        );
    }

    private BigDecimal getBalance(UUID walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM wallets WHERE id = ?",
                BigDecimal.class,
                walletId
        );
    }


    @Test
    void concurrentTransfersMustNotOverspendSenderWallet() throws Exception {
        // The test fixture should seed 500 MYR in the sender wallet
        // and 50 MYR in the receiver wallet.
        assertEquals(
                0,
                new BigDecimal("500.0000")
                        .compareTo(getBalance(senderWalletId))
        );

        int requestCount = 5;
        BigDecimal transferAmount = new BigDecimal("150.0000");

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        long transactionsBefore = ledgerTransactionRepository.count();

        try {
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start transfers"
                        );
                    }

                    try {
                        transferService.transfer(
                                senderEmail,
                                senderWalletId,
                                receiverWalletId,
                                transferAmount
                        );
                        return true;
                    } catch (IllegalArgumentException ex) {
                        // Expected for requests that cannot be funded.
                        return false;
                    }
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready in time"
            );

            start.countDown();

            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            }

            assertEquals(3, successes.get());
            assertEquals(2, failures.get());

        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(
                0,
                new BigDecimal("50.0000")
                        .compareTo(getBalance(senderWalletId))
        );

        assertEquals(
                0,
                new BigDecimal("500.0000")
                        .compareTo(getBalance(receiverWalletId))
        );

        assertEquals(
                transactionsBefore + 3,
                ledgerTransactionRepository.count()
        );
    }

}

