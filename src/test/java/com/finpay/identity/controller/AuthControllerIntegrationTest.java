package com.finpay.identity.controller;

import com.finpay.identity.entity.RefreshToken;
import com.finpay.identity.entity.User;
import com.finpay.identity.repository.RefreshTokenRepository;
import com.finpay.identity.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("finpay_test")
            .withUsername("finpay")
            .withPassword("finpay");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @Transactional
    void register_shouldCreateUser() throws Exception {

        String request = """
            {
                "email": "integration@example.com",
                "password": "password123",
                "firstName": "Integration",
                "lastName": "Test"
            }
            """;

        mockMvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            )
            .andExpect(status().isCreated());

        User user =
            userRepository
                .findByEmailIgnoreCase(
                    "integration@example.com"
                )
                .orElseThrow();

        assertThat(user.getEmail())
            .isEqualTo("integration@example.com");

        assertThat(user.getPasswordHash())
            .isNotEqualTo("password123");

        assertThat(user.getRoles())
            .hasSize(1);

        assertThat(
            user.getRoles()
                .iterator()
                .next()
                .getName()
        ).isEqualTo("USER");
    }

    @Test
    void login_shouldCreateRefreshToken() throws Exception {

        String email = "login@example.com";

        mockMvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "email": "login@example.com",
                            "password": "password123",
                            "firstName": "Login",
                            "lastName": "Test"
                        }
                        """)
            )
            .andExpect(status().isCreated());

        mockMvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "email": "login@example.com",
                            "password": "password123"
                        }
                        """)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900));

        User user =
            userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow();

        List<RefreshToken> tokens =
            refreshTokenRepository.findAll();

        assertThat(tokens)
            .hasSize(1);

        assertThat(tokens.getFirst().getUser().getId())
            .isEqualTo(user.getId());

        assertThat(tokens.getFirst().getRevokedAt())
            .isNull();
    }
}