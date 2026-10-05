package com.sporthub.identity;

import com.sporthub.identity.domain.*;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.OtpCodeRepository;
import com.sporthub.identity.repository.RefreshTokenRepository;
import com.sporthub.identity.repository.UserRepository;
import com.sporthub.identity.service.*;
import com.sporthub.identity.web.dto.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "jwt.secret=test-only-secret-that-is-at-least-32-bytes-long",
        "spring.data.redis.password=test-only",
        "spring.rabbitmq.password=test-only",
        "spring.rabbitmq.username=test-only",
        "sporthub.identity.verification-expiration-minutes=15",
        "sporthub.identity.password-reset-expiration-minutes=15",
        "sporthub.identity.frontend-base-url=http://localhost:3000",
        "sporthub.identity.mail-from=no-reply@sporthub.local",
        "sporthub.identity.secure-cookie=false"
})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class IdentityServiceIntegrationTest {
    static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("SPORTHUB_TEST_DB_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> System.getenv("SPORTHUB_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("SPORTHUB_TEST_DB_PASSWORD"));
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("identity_db")
                .withUsername("sporthub")
                .withPassword("sporthub-test");
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) postgres.stop();
    }

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired UserRepository userRepository;
    @Autowired OtpCodeRepository otpRepository;
    @Autowired RefreshTokenRepository refreshRepository;
    @Autowired Validator validator;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Autowired com.sporthub.identity.service.SessionValidationService sessionValidation;
    @Autowired com.sporthub.identity.security.JwtService jwtService;
    @MockBean MailDeliveryService mailDelivery;

    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, String> resetCodes = new ConcurrentHashMap<>();
    private final RequestMetadata metadata = new RequestMetadata("127.0.0.1", "identity-integration-test");

    @BeforeEach
    void captureOutboundCodes() {
        reset(mailDelivery);
        verificationCodes.clear();
        resetCodes.clear();
        doAnswer(invocation -> {
            verificationCodes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mailDelivery).sendVerification(anyString(), anyString(), anyString(), any(Instant.class));
        doAnswer(invocation -> {
            resetCodes.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(mailDelivery).sendPasswordReset(anyString(), any(UUID.class), anyString(), any(Instant.class));
    }

    @Test
    void registrationRequiresUniqueEmailAndPhoneAndStartsWithoutRole() {
        String email = uniqueEmail("registration");
        String phone = uniquePhone();
        RegisterResult result = authService.register(registration(email, phone), metadata);

        User user = userRepository.findById(result.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getRoles()).isEmpty();
        assertThat(verificationCodes).containsKey(email);

        assertThatThrownBy(() -> authService.register(registration(email, uniquePhone()), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Email is already registered");
        assertThatThrownBy(() -> authService.register(registration(uniqueEmail("phone"), phone), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Phone is already registered");
    }

    @Test
    void verificationValidatesCodeAndExpiryThenGrantsCustomer() {
        String email = uniqueEmail("verification");
        RegisterResult registration = authService.register(registration(email, null), metadata);

        assertThatThrownBy(() -> authService.verify(
                new VerificationRequest(null, registration.verificationChallengeId(), "000000"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Challenge is invalid");
        assertThat(otpRepository.findById(registration.verificationChallengeId()).orElseThrow().getAttempts())
                .isEqualTo(1);

        VerificationResult verified = authService.verify(
                new VerificationRequest(null, registration.verificationChallengeId(), verificationCodes.get(email)), metadata);
        User user = userRepository.findById(verified.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);

        String expiredEmail = uniqueEmail("expired-verification");
        RegisterResult expired = authService.register(registration(expiredEmail, null), metadata);
        OtpCode challenge = otpRepository.findById(expired.verificationChallengeId()).orElseThrow();
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
        otpRepository.save(challenge);
        assertThatThrownBy(() -> authService.verify(
                new VerificationRequest(null, expired.verificationChallengeId(), verificationCodes.get(expiredEmail)), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Challenge has expired");
    }

    @Test
    void loginChecksPasswordVerificationAndLockThenRefreshRotatesAndLogoutRevokes() {
        String unverifiedEmail = uniqueEmail("unverified-login");
        authService.register(registration(unverifiedEmail, null), metadata);
        assertThatThrownBy(() -> authService.login(new LoginRequest(unverifiedEmail, "Password123!"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Account is not active");

        String email = uniqueEmail("login");
        User user = activate(email);
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong-password"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("invalid");

        SessionTokens login = authService.login(new LoginRequest(email, "Password123!"), metadata);
        assertThat(login.response().accessToken()).isNotBlank();
        assertThatThrownBy(() -> authService.refresh(null, metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Refresh token is invalid or expired");
        SessionTokens rotated = authService.refresh(login.refreshToken(), metadata);
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), metadata))
                .isInstanceOf(IdentityException.class);

        authService.logout(rotated.refreshToken(), null, user.getId(), metadata);
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken(), metadata))
                .isInstanceOf(IdentityException.class);

        User currentUser = userRepository.findById(user.getId()).orElseThrow();
        currentUser.setStatus(AccountStatus.LOCKED);
        userRepository.save(currentUser);
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Password123!"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Account is not active");
    }

    @Test
    void passwordResetIsOneTimeAndRejectsInvalidOrExpiredCode() {
        String email = uniqueEmail("reset");
        User user = activate(email);
        authService.forgotPassword(new ForgotPasswordRequest(email), metadata);
        OtpCode resetChallenge = otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(), OtpPurpose.PASSWORD_RESET)
                .getFirst();

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest(resetChallenge.getId(), "000000", "ChangedPassword123!"), metadata))
                .isInstanceOf(IdentityException.class);
        authService.resetPassword(new ResetPasswordRequest(
                resetChallenge.getId(), resetCodes.get(email), "ChangedPassword123!"), metadata);
        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(
                resetChallenge.getId(), resetCodes.get(email), "AnotherPassword123!"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Challenge has already been used");
        assertThat(authService.login(new LoginRequest(email, "ChangedPassword123!"), metadata).response().accessToken())
                .isNotBlank();

        authService.forgotPassword(new ForgotPasswordRequest(email), metadata);
        OtpCode expired = otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(), OtpPurpose.PASSWORD_RESET)
                .getFirst();
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        otpRepository.save(expired);
        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest(expired.getId(), resetCodes.get(email), "AnotherPassword123!"), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Challenge has expired");
    }

    @Test
    void profileSupportsPermittedFieldsAndPersistenceSupportsMultipleRoles() {
        String email = uniqueEmail("profile");
        User user = activate(email);
        user.getRoles().add(Role.OWNER);
        userRepository.save(user);

        UserProfileView before = profileService.get(user.getId());
        assertThat(before.roles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.OWNER);
        UserProfileView updated = profileService.update(user.getId(), new UpdateProfileRequest(
                "Updated Member", null, null, "https://cdn.sporthub.local/avatar.png", LocalDate.of(2000, 1, 2)), metadata);
        assertThat(updated.fullName()).isEqualTo("Updated Member");
        assertThat(updated.dateOfBirth()).isEqualTo(LocalDate.of(2000, 1, 2));
        assertThat(profileService.get(user.getId()).avatarUrl()).contains("avatar.png");

        assertThat(validator.validate(new UpdateProfileRequest(null, null, "invalid-phone", null, null)))
                .isNotEmpty();
        assertThatThrownBy(() -> profileService.update(user.getId(),
                new UpdateProfileRequest(null, null, null, null, null), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("At least one");
    }

    private User activate(String email) {
        RegisterResult result = authService.register(registration(email, null), metadata);
        authService.verify(new VerificationRequest(null, result.verificationChallengeId(), verificationCodes.get(email)), metadata);
        return userRepository.findById(result.userId()).orElseThrow();
    }

    @Test
    void phoneOnlyRegistrationVerifiesAndCanLogin() {
        String phone = uniquePhone();
        var result = authService.register(registration(null, phone), metadata);
        authService.verify(new VerificationRequest(null, result.verificationChallengeId(), verificationCodes.get(phone)), metadata);
        assertThat(authService.login(new LoginRequest(phone, "Password123!"), metadata).response().user().phoneVerified()).isTrue();
    }

    @Test
    void apiLoginCookieAndProfileRejectRevokedAccessSession() throws Exception {
        String email=uniqueEmail("http-session"); activate(email);
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType("application/json").content("{\"identifier\":\""+email+"\",\"password\":\"Password123!\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax");
        String token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var principal=jwtService.parseAccessToken(token);
        authService.logout(null, principal.sessionId(), principal.userId(), metadata);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void resetRevokesExistingSessionsAndVerificationCannotBeReused() {
        String email=uniqueEmail("revocation"); var user=activate(email);
        var session=authService.login(new LoginRequest(email,"Password123!"),metadata);
        var principal=jwtService.parseAccessToken(session.response().accessToken());
        assertThat(sessionValidation.validate(principal).userId()).isEqualTo(user.getId());
        authService.forgotPassword(new ForgotPasswordRequest(email),metadata);
        var challenge=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();
        authService.resetPassword(new ResetPasswordRequest(challenge.getId(),resetCodes.get(email),"ChangedPassword123!"),metadata);
        assertThatThrownBy(()->sessionValidation.validate(principal)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->authService.refresh(session.refreshToken(),metadata)).isInstanceOf(IdentityException.class);
    }

    private RegisterRequest registration(String email, String phone) {
        return new RegisterRequest("SportHub Member", email, phone, "Password123!");
    }

    private String uniqueEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID() + "@sporthub.local";
    }

    private String uniquePhone() {
        long digits = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        return "+84" + String.format("%09d", digits);
    }
}
