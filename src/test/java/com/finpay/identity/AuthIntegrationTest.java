package com.finpay.identity;

import com.finpay.identity.entity.User;
import com.finpay.identity.entity.UserStatus;
import com.finpay.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class AuthIntegrationTest {

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
    private UserRepository userRepository;

    @Test
    void shouldPersistUserInPostgres() {

        User user = new User(
            "integration@example.com",
            "hashed-password",
            "Integration",
            "Test",
            UserStatus.ACTIVE
        );

        User savedUser =
            userRepository.saveAndFlush(user);

        assertThat(savedUser.getId())
            .isNotNull();

        assertThat(
            userRepository
                .findByEmailIgnoreCase(
                    "integration@example.com"
                )
        )
        .isPresent();
    }
}