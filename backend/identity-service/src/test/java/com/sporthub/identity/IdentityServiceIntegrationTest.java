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
        ,"sporthub.identity.notification-delay-ms=3600000","sporthub.identity.admin-expiry-delay-ms=3600000","spring.rabbitmq.listener.simple.auto-startup=false"
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
    @Autowired AdminAccountService adminAccounts;
    @Autowired FacilityNoticeConsumer facilityNotices;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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
    void contactChangeKeepsCurrentLoginUntilVerifiedAndResendInvalidatesTheOldCode() throws Exception {
        var user = activate(uniqueEmail("contact-current"));
        var other = activate(uniqueEmail("contact-other"));
        String target = uniqueEmail("contact-new");
        var token = authService.login(new LoginRequest(user.getEmail(), "Password123!"), metadata).response().accessToken();
        profileService.update(user.getId(), new UpdateProfileRequest(null, target, null, null, null), metadata);
        assertThat(profileService.get(user.getId()).email()).isEqualTo(user.getEmail());
        var challenge = profileService.contactChallenges(user.getId()).getFirst();
        assertThat(challenge.recipient()).isEqualTo(target);
        assertThat(challenge.channel()).isEqualTo("EMAIL");
        profileService.update(user.getId(), new UpdateProfileRequest(null, target, null, null, null), metadata);
        assertThat(profileService.contactChallenges(user.getId())).hasSize(1);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/contact-challenges")
                .header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].id").value(challenge.id().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].codeHash").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].tokenHash").doesNotExist());
        var otherToken = authService.login(new LoginRequest(other.getEmail(), "Password123!"), metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/contact-challenges")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/users/me/contact-challenges/EMAIL/resend")
                .header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests());
        jdbc.update("UPDATE otp_codes SET created_at=NOW()-interval '61 seconds' WHERE id=?", challenge.id());
        String originalCode = verificationCodes.get(target);
        var replacement = profileService.resendContact(user.getId(), "EMAIL", metadata);
        assertThat(replacement.id()).isNotEqualTo(challenge.id());
        assertThatThrownBy(() -> authService.verify(new VerificationRequest(null, challenge.id(), originalCode), metadata))
                .isInstanceOf(IdentityException.class).hasMessageContaining("already been used");
        authService.verify(new VerificationRequest(null, replacement.id(), verificationCodes.get(target)), metadata);
        assertThat(profileService.contactChallenges(user.getId())).isEmpty();
        assertThat(authService.login(new LoginRequest(target, "Password123!"), metadata).response().user().emailVerified()).isTrue();
        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "Password123!"), metadata))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void phoneChallengeExpiryAndResendDoNotChangeContactBeforeSuccessfulVerification() {
        var user = activate(uniqueEmail("phone-change"));
        String phone = uniquePhone();
        profileService.update(user.getId(), new UpdateProfileRequest(null, null, phone, null, null), metadata);
        var challenge = profileService.contactChallenges(user.getId()).getFirst();
        assertThat(challenge.channel()).isEqualTo("PHONE");
        assertThat(profileService.get(user.getId()).phone()).isNull();
        jdbc.update("UPDATE otp_codes SET expires_at=NOW()-interval '1 second',created_at=NOW()-interval '61 seconds' WHERE id=?", challenge.id());
        assertThat(profileService.contactChallenges(user.getId()).getFirst().usable()).isFalse();
        assertThatThrownBy(() -> authService.verify(new VerificationRequest(null, challenge.id(), verificationCodes.get(phone)), metadata))
                .isInstanceOf(IdentityException.class).hasMessageContaining("expired");
        var resent = profileService.resendContact(user.getId(), "PHONE", metadata);
        authService.verify(new VerificationRequest(null, resent.id(), verificationCodes.get(phone)), metadata);
        assertThat(profileService.get(user.getId()).phone()).isEqualTo(phone);
        assertThat(authService.login(new LoginRequest(phone, "Password123!"), metadata).response().user().phoneVerified()).isTrue();
        assertThat(profileService.get(user.getId()).roles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void adminLockAndRoleChangeRevokeEverySessionAndAuditCannotBeModified() {
        var admin=activate(uniqueEmail("admin-controls"));admin.getRoles().add(Role.ADMIN);userRepository.saveAndFlush(admin);
        var adminSession=authService.login(new LoginRequest(admin.getEmail(),"Password123!"),metadata);var actor=jwtService.parseAccessToken(adminSession.response().accessToken());
        var user=activate(uniqueEmail("lock-target"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);var principal=jwtService.parseAccessToken(session.response().accessToken());
        var locked=adminAccounts.lock(user.getId(),new com.sporthub.identity.web.dto.AdminAccountDtos.LockInput("Test policy violation","SEVEN_DAYS"),actor,metadata);assertThat(locked.status()).isEqualTo(AccountStatus.LOCKED);assertThat(locked.lockedUntil()).isAfter(Instant.now());
        assertThatThrownBy(()->sessionValidation.validate(principal)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->authService.refresh(session.refreshToken(),metadata)).isInstanceOf(IdentityException.class);
        adminAccounts.unlock(user.getId(),new com.sporthub.identity.web.dto.AdminAccountDtos.Reason("Reviewed"),actor,metadata);assertThatThrownBy(()->sessionValidation.validate(principal)).isInstanceOf(IllegalArgumentException.class);
        var newSession=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);adminAccounts.roles(user.getId(),new com.sporthub.identity.web.dto.AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER,Role.STAFF),"Assigned staff role"),actor,metadata);
        assertThatThrownBy(()->sessionValidation.validate(jwtService.parseAccessToken(newSession.response().accessToken()))).isInstanceOf(IllegalArgumentException.class);
        var detail=adminAccounts.detail(user.getId(),actor);assertThat(detail.account().roles()).contains(Role.CUSTOMER,Role.STAFF);assertThat(detail.audit()).extracting(com.sporthub.identity.web.dto.AdminAccountDtos.Audit::action).contains("ADMIN_ACCOUNT_LOCKED","ADMIN_ACCOUNT_UNLOCKED","ADMIN_ROLES_CHANGED");
        assertThatThrownBy(()->jdbc.update("DELETE FROM audit_log WHERE entity_type='USER' AND entity_id=?",user.getId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->adminAccounts.search(null,null,null,principal)).isInstanceOf(IdentityException.class);assertThatThrownBy(()->adminAccounts.lock(admin.getId(),new com.sporthub.identity.web.dto.AdminAccountDtos.LockInput("Self","PERMANENT"),actor,metadata)).isInstanceOf(IdentityException.class);
    }
    @Test
    void expiredTemporaryLockAllowsFreshLoginAndKeepsSessionsRevoked(){
        var admin=activate(uniqueEmail("expiry-admin"));admin.getRoles().add(Role.ADMIN);userRepository.saveAndFlush(admin);var actor=new com.sporthub.identity.security.AuthenticatedUser(admin.getId(),admin.getEmail(),java.util.Set.of(Role.ADMIN),UUID.randomUUID());var user=activate(uniqueEmail("expiry-target"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        adminAccounts.lock(user.getId(),new com.sporthub.identity.web.dto.AdminAccountDtos.LockInput("Temporary","SEVEN_DAYS"),actor,metadata);jdbc.update("UPDATE users SET locked_until=NOW()-interval '1 second' WHERE id=?",user.getId());adminAccounts.expireLocks();assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);assertThatThrownBy(()->authService.refresh(session.refreshToken(),metadata)).isInstanceOf(IdentityException.class);assertThat(authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata).response().user().status()).isEqualTo(AccountStatus.ACTIVE);
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

    @Test void facilityDecisionsNotifyTheVerifiedOwnerOnceEvenWithBusinessReplay() {
        var user=activate(uniqueEmail("facility-notice"));UUID review=UUID.randomUUID(),facility=UUID.randomUUID();var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var event=json.valueToTree(com.sporthub.common.event.DomainEvent.create("facility.reviewed",1,"facility-service",facility,Map.of("reviewId",review,"facilityId",facility,"ownerId",user.getId(),"facilityName","Controlled Facility","state","APPROVED","reason","Review approved")));
        facilityNotices.apply(event);facilityNotices.apply(event);((com.fasterxml.jackson.databind.node.ObjectNode)event).put("eventId",UUID.randomUUID().toString());facilityNotices.apply(event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE source_key=?",Integer.class,"facility-review:"+review)).isEqualTo(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)event).put("producer","untrusted-service");assertThatThrownBy(()->facilityNotices.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID() + "@sporthub.local";
    }

    private String uniquePhone() {
        long digits = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        return "+84" + String.format("%09d", digits);
    }
}
