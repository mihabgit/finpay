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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
    private String idempotencyKey;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString();

        idempotencyKey = UUID.randomUUID().toString();

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
                        .header("Idempotency-key", idempotencyKey)
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
                        .header("Idempotency-key", idempotencyKey)
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
                        .header("Idempotency-key", idempotencyKey)
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
                        .header("Idempotency-key", idempotencyKey)
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
                        .header("Idempotency-key", idempotencyKey)
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
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(
                                senderWalletId, receiverWalletId, "-10.0000"
                        )))
                .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
    }

    @Test
    void transfer_withZeroAmount_shouldBeRejectedWithoutChangingState() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(senderWalletId, receiverWalletId, "0.0000")))
                .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
        assertEquals(transactionsBefore, ledgerTransactionRepository.count());
    }

    @Test
    void transfer_withoutIdempotencyKey_shouldBeRejectedWithoutChangingState() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(senderWalletId, receiverWalletId, "10.0000")))
                .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
        assertEquals(transactionsBefore, ledgerTransactionRepository.count());
    }

    @Test
    void transfer_withInvalidAuthenticationToken_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-token")
                        .header("Idempotency-key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(senderWalletId, receiverWalletId, "10.0000")))
                .andExpect(status().isForbidden());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
    }

    @Test
    void transfer_withMalformedJson_shouldBeRejectedWithoutChangingState() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
        assertEquals(transactionsBefore, ledgerTransactionRepository.count());
    }

    @Test
    void transfer_withNonexistentSenderWallet_shouldReturnNotFound() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();
        UUID missingWalletId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(missingWalletId, receiverWalletId, "10.0000")))
                .andExpect(status().isNotFound());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
        assertEquals(transactionsBefore, ledgerTransactionRepository.count());
    }

    @Test
    void transfer_withNonexistentReceiverWallet_shouldReturnNotFound() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();
        UUID missingWalletId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(senderWalletId, missingWalletId, "10.0000")))
                .andExpect(status().isNotFound());

        assertBalance(senderWalletId, "500.0000");
        assertBalance(receiverWalletId, "50.0000");
        assertEquals(transactionsBefore, ledgerTransactionRepository.count());
    }

    @Test
    void transfer_withSameIdempotencyKeyAndSamePayload_shouldExecuteOnlyOnce() throws Exception {
        long transactionsBefore = ledgerTransactionRepository.count();
        String request = transferRequest(senderWalletId, receiverWalletId, "100.0000");

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        assertBalance(senderWalletId, "400.0000");
        assertBalance(receiverWalletId, "150.0000");
        assertEquals(transactionsBefore + 1, ledgerTransactionRepository.count());
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
                        .header("Idempotency-key", UUID.randomUUID().toString())
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + senderToken)
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of(
                                        "senderWalletId", fromWalletId,
                                        "receiverWalletId", toWalletId,
                                        "amount", amount
                                )
                        )))
                .andExpect(status().isBadRequest());

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
                                transferAmount,
                                UUID.randomUUID().toString()
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


    @Test
    void concurrentRequestsWithSameIdempotencyKey_shouldExecuteTransferOnlyOnce()
            throws Exception {

        int requestCount = 5;
        BigDecimal transferAmount = new BigDecimal("100.0000");

        BigDecimal senderBalanceBefore = getBalance(senderWalletId);
        BigDecimal receiverBalanceBefore = getBalance(receiverWalletId);
        long transactionsBefore = ledgerTransactionRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start transfers"
                        );
                    }

                    var response = transferService.transfer(
                            senderEmail,
                            senderWalletId,
                            receiverWalletId,
                            transferAmount,
                            idempotencyKey
                    );

                    return response.reference();

                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready in time"
            );

            // Release all workers as close together as possible.
            start.countDown();

            List<String> references = new ArrayList<>();

            for (Future<String> result : results) {
                references.add(result.get(30, TimeUnit.SECONDS));
            }

            // All requests must return the same transfer reference.
            assertThat(references)
                    .hasSize(requestCount)
                    .doesNotContainNull()
                    .containsOnly(references.getFirst());

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Transfer workers did not terminate in time"
            );
        }

        // The shared request must debit and credit exactly once.
        assertEquals(
                0,
                senderBalanceBefore.subtract(transferAmount)
                        .compareTo(getBalance(senderWalletId))
        );

        assertEquals(
                0,
                receiverBalanceBefore.add(transferAmount)
                        .compareTo(getBalance(receiverWalletId))
        );

        // Only one ledger transaction should have been created.
        assertEquals(
                transactionsBefore + 1,
                ledgerTransactionRepository.count()
        );
    }


    @Test
    void concurrentRequestsWithSameIdempotencyKeyAndDifferentPayload_shouldNotDoubleTransfer()
            throws Exception {
        BigDecimal senderBalanceBefore = getBalance(senderWalletId);
        BigDecimal receiverBalanceBefore = getBalance(receiverWalletId);
        long transactionsBefore = ledgerTransactionRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Integer> firstRequest = () -> performTransferAndGetStatus(
                    "100.0000", ready, start);
            Callable<Integer> secondRequest = () -> performTransferAndGetStatus(
                    "200.0000", ready, start);

            Future<Integer> first = executor.submit(firstRequest);
            Future<Integer> second = executor.submit(secondRequest);

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready in time");
            start.countDown();

            List<Integer> statuses = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Transfer workers did not terminate in time");
        }

        // Exactly one of the two payloads may win; balances must reflect one transfer only.
        BigDecimal senderAfter = getBalance(senderWalletId);
        BigDecimal receiverAfter = getBalance(receiverWalletId);
        BigDecimal senderDebit = senderBalanceBefore.subtract(senderAfter);
        BigDecimal receiverCredit = receiverAfter.subtract(receiverBalanceBefore);

        assertThat(senderDebit).isIn(new BigDecimal("100.0000"), new BigDecimal("200.0000"));
        assertEquals(0, senderDebit.compareTo(receiverCredit));
        assertEquals(transactionsBefore + 1, ledgerTransactionRepository.count());
    }

    private int performTransferAndGetStatus(
            String amount,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start transfers");
        }

        return mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(senderToken))
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(senderWalletId, receiverWalletId, amount)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void transfer_withSameIdempotencyKeyAndDifferentAmount_shouldReturnConflict()
            throws Exception {

        long transactionsBefore = ledgerTransactionRepository.count();

        // First request: succeeds and transfers 100 MYR.
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(senderToken))
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(
                                senderWalletId,
                                receiverWalletId,
                                "100.0000"
                        )))
                .andExpect(status().isCreated());

        // Verify the first transfer succeeded.
        assertBalance(senderWalletId, "400.0000");
        assertBalance(receiverWalletId, "150.0000");

        long transactionsAfterFirstTransfer =
                ledgerTransactionRepository.count();

        assertEquals(transactionsBefore + 1, transactionsAfterFirstTransfer);

        // Retry with the SAME key but a DIFFERENT amount.
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(senderToken))
                        .header("Idempotency-key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferRequest(
                                senderWalletId,
                                receiverWalletId,
                                "200.0000"
                        )))
                .andExpect(status().isConflict());

        // The rejected request must not affect balances or create a transaction.
        assertBalance(senderWalletId, "400.0000");
        assertBalance(receiverWalletId, "150.0000");

        assertEquals(
                transactionsAfterFirstTransfer,
                ledgerTransactionRepository.count()
        );
    }

}
