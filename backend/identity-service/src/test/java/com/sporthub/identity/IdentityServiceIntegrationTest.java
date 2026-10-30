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
        "GOOGLE_CLIENT_ID=test-google-client-id",
        "GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "GOOGLE_REDIRECT_URI=http://localhost:13000/api/v1/auth/oauth2/callback/google",
        "spring.data.redis.password=test-only",
        "spring.rabbitmq.password=test-only",
        "spring.rabbitmq.username=test-only",
        "sporthub.identity.verification-expiration-minutes=15",
        "sporthub.identity.password-reset-expiration-minutes=15",
        "sporthub.identity.frontend-base-url=http://localhost:3000",
        "sporthub.identity.mail-from=no-reply@sporthub.local",
        "sporthub.identity.secure-cookie=false"
        ,"sporthub.identity.notification-delay-ms=3600000","sporthub.identity.admin-expiry-delay-ms=3600000","sporthub.identity.application-delay-ms=3600000","spring.rabbitmq.listener.simple.auto-startup=false"
})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class IdentityServiceIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    static final String applicationTestKey=java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("OWNER_APPLICATION_DATA_KEY",()->applicationTestKey);
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
    @Autowired GroupReminderConsumer groupReminders;
    @Autowired IdentityNotificationJob notificationJob;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean MailDeliveryService mailDelivery;
    @MockBean LoginAttemptLimiter loginAttempts;
    @MockBean OwnerApplicationDependencies applicationDependencies;
    @Autowired OwnerApplicationService applications;
    @Autowired OwnerApplicationCipher applicationCipher;
    @Autowired StaffBindingService staff;
    @MockBean StaffFacilityAccess staffFacilityAccess;
    private final Map<UUID,UUID> staffFacilityOwners=new ConcurrentHashMap<>();

    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, String> resetCodes = new ConcurrentHashMap<>();
    private final RequestMetadata metadata = new RequestMetadata("127.0.0.1", "identity-integration-test");

    @BeforeEach
    void captureOutboundCodes() {
        reset(mailDelivery);
        reset(applicationDependencies);
        reset(staffFacilityAccess);staffFacilityOwners.clear();
        doAnswer(a->{UUID facility=a.getArgument(0);var actor=a.<com.sporthub.identity.security.AuthenticatedUser>getArgument(1);if(!actor.roles().contains(Role.OWNER)||!actor.userId().equals(staffFacilityOwners.get(facility)))throw new com.sporthub.common.exception.ForbiddenException("Facility is outside your ownership");return null;}).when(staffFacilityAccess).owner(any(),any(),any());
        var applicationJson=new com.fasterxml.jackson.databind.ObjectMapper();
        org.mockito.Mockito.when(applicationDependencies.facility(any(UUID.class),anyString(),anyMap(),any())).thenAnswer(invocation->{
            String command=invocation.getArgument(1);return command.equals("submit")?applicationJson.readTree("{\"snapshot\":{\"facility\":{\"name\":\"First facility\"},\"hours\":[],\"prices\":[]}}"):applicationJson.createObjectNode();
        });
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
    void googleLoginCreatesCustomerAndIssuesSportHubJwtAndRefreshSession() {
        String email = uniqueEmail("google-new");
        GoogleIdentity identity = new GoogleIdentity(
                "google-sub-" + UUID.randomUUID(), email, true, "Google Member", "https://example.com/avatar.png");

        SessionTokens first = authService.loginWithGoogle(identity, metadata);
        User user = userRepository.findByGoogleSub(identity.googleSub()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(user.getProfile().getFullName()).isEqualTo("Google Member");
        assertThat(user.getProfile().getAvatarUrl()).isEqualTo(identity.avatarUrl());
        assertThat(sessionValidation.validate(jwtService.parseAccessToken(first.response().accessToken())).userId())
                .isEqualTo(user.getId());
        assertThat(authService.refresh(first.refreshToken(), metadata).response().user().id()).isEqualTo(user.getId());

        SessionTokens repeated = authService.loginWithGoogle(identity, metadata);
        assertThat(repeated.response().user().id()).isEqualTo(user.getId());
        assertThat(userRepository.findAll().stream().filter(candidate -> email.equals(candidate.getEmail())).toList())
                .hasSize(1);
    }

    @Test
    void googleAuthorizationStartsOpenIdConnectCodeFlow() throws Exception {
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/auth/oauth2/authorization/google"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", org.hamcrest.Matchers.containsString("accounts.google.com")));
    }

    @Test
    void googleLoginSafelyLinksOnlyVerifiedExistingEmailAndRejectsUnverifiedEmail() {
        User existing = activate(uniqueEmail("google-link"));
        String originalPasswordHash = existing.getPasswordHash();
        GoogleIdentity linked = new GoogleIdentity(
                "google-sub-" + UUID.randomUUID(), existing.getEmail().toUpperCase(), true,
                "Google Name", "https://example.com/google-avatar.png");

        SessionTokens session = authService.loginWithGoogle(linked, metadata);
        User current = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(current.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(current.getGoogleSub()).isEqualTo(linked.googleSub());
        assertThat(current.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(session.response().user().id()).isEqualTo(existing.getId());
        assertThat(authService.login(new LoginRequest(existing.getEmail(), "Password123!"), metadata)
                .response().user().id()).isEqualTo(existing.getId());

        String unverifiedEmail = uniqueEmail("google-unverified");
        authService.register(registration(unverifiedEmail, null), metadata);
        assertThatThrownBy(() -> authService.loginWithGoogle(
                new GoogleIdentity("google-sub-" + UUID.randomUUID(), unverifiedEmail, true, "Member", null), metadata))
                .isInstanceOf(IdentityException.class)
                .hasMessage("Verify this email address before linking a Google account");
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

    private com.fasterxml.jackson.databind.JsonNode reminderEvent(User recipient){
        var json=signupJson;UUID group=UUID.randomUUID();
        return json.valueToTree(com.sporthub.common.event.DomainEvent.create("booking.group.payment.reminder",1,"booking-service",group,Map.of("groupId",group,"ownerId",UUID.randomUUID(),"memberId",UUID.randomUUID(),"recipientId",recipient.getId(),"amount",50000,"currency","VND","deadline",Instant.parse("2030-01-01T03:00:00Z"),"timezone","Asia/Ho_Chi_Minh","acceptedAt",Instant.now())));
    }
    @Test void groupRemindersRouteVerifiedContactRenderAmountDeadlineLinkAndDeduplicate(){
        var user=activate(uniqueEmail("group-reminder"));var event=reminderEvent(user);groupReminders.apply(event);groupReminders.apply(event);
        var rows=jdbc.queryForList("SELECT * FROM identity_notifications WHERE source_key=?","group-reminder:"+event.path("eventId").asText());assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("recipient")).isEqualTo(user.getEmail());assertThat(rows.getFirst().get("body").toString()).contains("50.000 VND","01/01/2030 10:00","/customer/groups/"+event.path("payload").path("groupId").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)event).put("producer","untrusted-service");assertThatThrownBy(()->groupReminders.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    }
    @Test void groupReminderDownstreamDeliveryFailureStaysPendingAndExistingJobRetries(){
        var user=activate(uniqueEmail("reminder-retry"));var event=reminderEvent(user);groupReminders.apply(event);String source="group-reminder:"+event.path("eventId").asText();
        jdbc.update("UPDATE identity_notifications SET created_at='1970-01-01T00:00:00Z' WHERE source_key=?",source);
        org.mockito.Mockito.doThrow(new IllegalStateException("Controlled SMTP outage")).when(mailDelivery).sendNotification(eq(user.getEmail()),anyString(),anyString());
        notificationJob.deliver();var failed=jdbc.queryForMap("SELECT * FROM identity_notifications WHERE source_key=?",source);
        assertThat(failed.get("state")).isEqualTo("PENDING");assertThat(failed.get("sent_at")).isNull();assertThat(failed.get("attempts")).isEqualTo(1);assertThat(failed.get("last_error")).isEqualTo("IllegalStateException");
        org.mockito.Mockito.doNothing().when(mailDelivery).sendNotification(eq(user.getEmail()),anyString(),anyString());jdbc.update("UPDATE identity_notifications SET next_attempt_at=NOW() WHERE source_key=?",source);notificationJob.deliver();
        assertThat(jdbc.queryForMap("SELECT * FROM identity_notifications WHERE source_key=?",source)).containsEntry("state","SENT");groupReminders.apply(event);
        org.mockito.Mockito.verify(mailDelivery,org.mockito.Mockito.times(2)).sendNotification(eq(user.getEmail()),anyString(),anyString());
    }
    @Test void groupReminderReachesInAppInboxWithoutVerifiedContactAndCanBeMarkedRead(){
        var user=activate(uniqueEmail("reminder-inbox"));jdbc.update("UPDATE users SET email_verified=false,phone_verified=false WHERE id=?",user.getId());
        var event=reminderEvent(user);((com.fasterxml.jackson.databind.node.ObjectNode)event.path("payload")).put("groupName","Nhóm 1");groupReminders.apply(event);
        var row=jdbc.queryForMap("SELECT * FROM identity_notifications WHERE source_key=?","group-reminder:"+event.path("eventId").asText());
        assertThat(row.get("state")).isEqualTo("IN_APP_ONLY");assertThat(row.get("recipient")).isNull();
        var principal=new com.sporthub.identity.security.AuthenticatedUser(user.getId(),user.getEmail(),java.util.Set.of(),UUID.randomUUID());
        var inbox=notifications.mine(principal).getData();
        assertThat(inbox.unreadCount()).isEqualTo(1);assertThat(inbox.items().getFirst().body()).contains("nhóm Nhóm 1").doesNotContain("http");
        assertThat(inbox.items().getFirst().link()).isEqualTo("/customer/groups/"+event.path("payload").path("groupId").asText());
        var stranger=new com.sporthub.identity.security.AuthenticatedUser(UUID.randomUUID(),"x@sports.test",java.util.Set.of(),UUID.randomUUID());
        notifications.read(inbox.items().getFirst().id(),stranger);assertThat(notifications.mine(principal).getData().unreadCount()).isEqualTo(1);
        notifications.read(inbox.items().getFirst().id(),principal);assertThat(notifications.mine(principal).getData().unreadCount()).isZero();
    }
    @Autowired com.sporthub.identity.web.NotificationController notifications;
    @Test void malformedGroupReminderDoesNotCreateInboxOrNotice(){
        var user=activate(uniqueEmail("invalid-reminder"));var event=reminderEvent(user);((com.fasterxml.jackson.databind.node.ObjectNode)event.path("payload")).put("amount",-1);
        assertThatThrownBy(()->groupReminders.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE consumer='group-payment-reminder' AND event_id=?",Integer.class,UUID.fromString(event.path("eventId").asText()))).isZero();
    }

    @Autowired OwnerSignupService ownerSignup;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper signupJson;
    private org.springframework.mock.web.MockMultipartFile signupFile(){return new org.springframework.mock.web.MockMultipartFile("file","fixture.png","image/png",java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6T1kAAAAASUVORK5CYII="));}
    private RegisterRequest signupRequest(String email){
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)signupJson.valueToTree(contactApplicationInput("facility@example.test"));
        value.set("setup",signupJson.valueToTree(Map.of("courtCode","C1","courtName","First court","sportCategoryId",UUID.randomUUID(),"days",java.util.List.of(1,2,3,4,5,6,7),"opensAt","06:00","closesAt","22:00","slotMinutes",60,"pricePerSlot",100000)));
        return new RegisterRequest("Signup applicant",email,null,"Password123!",true,value);
    }
    private RegisterResult signup(RegisterRequest request,String key){return ownerSignup.register(request,key,signupFile(),signupFile(),signupFile(),metadata);}
    @Test void ownerSignupEntersExistingQueueWithoutRoleAndRequiresVerificationBeforeApproval(){
        var request=signupRequest(uniqueEmail("signup"));var result=signup(request,UUID.randomUUID().toString());var user=userRepository.findById(result.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);assertThat(user.getRoles()).isEmpty();assertThat(result.ownerApplication().state()).isEqualTo("PENDING_APPROVAL");assertThat(result.ownerApplication().userId()).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_applications WHERE user_id=?",Integer.class,user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT private_payload FROM owner_applications WHERE id=?",String.class,result.ownerApplication().id())).doesNotContain("TEST-IDENTITY-PRIVATE","TEST-BANK-PRIVATE");
        var admin=applicationAdmin();assertThat(applications.search(searchCriteria(Map.of("state","PENDING_APPROVAL","applicationId",result.ownerApplication().id().toString())),admin).items().stream().map(OwnerApplicationDtos.Summary::id)).contains(result.ownerApplication().id());
        assertThatThrownBy(()->applications.decide(result.ownerApplication().id(),approval(),admin)).isInstanceOf(IdentityException.class).hasMessageContaining("unverified");
        authService.verify(new VerificationRequest(null,result.verificationChallengeId(),verificationCodes.get(request.email())),metadata);
        var session=authService.login(new LoginRequest(request.email(),request.password()),metadata);assertThat(session.response().user().roles()).containsExactly(Role.CUSTOMER);
        applications.decide(result.ownerApplication().id(),approval(),admin);applications.decide(result.ownerApplication().id(),approval(),admin);
        assertThat(userRepository.findById(result.userId()).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER,Role.OWNER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE user_id=?",Integer.class,result.userId())).isEqualTo(1);
        assertThat(refreshRepository.findAll().stream().filter(t->t.getUser().getId().equals(result.userId())).allMatch(RefreshToken::isRevoked)).isTrue();
    }
    @Test void ownerSignupRetriesReuseReceiptChallengeApplicationAndAudit(){
        var request=signupRequest(uniqueEmail("signup-retry"));String key=UUID.randomUUID().toString();var first=signup(request,key);var repeat=signup(request,key);
        assertThat(repeat.userId()).isEqualTo(first.userId());assertThat(repeat.verificationChallengeId()).isEqualTo(first.verificationChallengeId());assertThat(repeat.ownerApplication().id()).isEqualTo(first.ownerApplication().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='OWNER_APPLICATION_CREATED'",Integer.class,first.ownerApplication().id())).isEqualTo(1);
        assertThatThrownBy(()->signup(request,UUID.randomUUID().toString())).isInstanceOf(IdentityException.class).hasMessageContaining("already registered");
        assertThatThrownBy(()->signup(new RegisterRequest(request.fullName(),request.email(),null,"WrongPassword123!",true,request.ownerApplication()),key)).isInstanceOf(IdentityException.class).hasMessageContaining("original registration");
        var changed=new RegisterRequest("Changed name",request.email(),null,request.password(),true,request.ownerApplication());assertThatThrownBy(()->signup(changed,key)).isInstanceOf(IdentityException.class).hasMessageContaining("original registration");
    }
    @Test void ownerSignupConcurrentRequestsCreateOnlyOneAccountApplicationSubmission()throws Exception{
        var request=signupRequest(uniqueEmail("signup-concurrent"));String key=UUID.randomUUID().toString();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var calls=pool.invokeAll(java.util.List.<java.util.concurrent.Callable<RegisterResult>>of(()->signup(request,key),()->signup(request,key)));var first=calls.get(0).get();assertThat(calls.get(1).get().userId()).isEqualTo(first.userId());assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,first.ownerApplication().id())).isEqualTo(1);}finally{pool.shutdownNow();}
    }
    @Test void ownerSignupLegacyReceiptLayoutsNormalizeWithoutDuplicates()throws Exception{
        var request=signupRequest(uniqueEmail("signup-legacy-receipt"));String key=UUID.randomUUID().toString();var initial=signup(request,key);
        String canonical=jdbc.queryForObject("SELECT request_hash FROM owner_signup_receipts WHERE user_id=?",String.class,initial.userId());
        var digest=java.security.MessageDigest.getInstance("SHA-256");String fileDigest=java.util.HexFormat.of().formatHex(digest.digest(signupFile().getBytes()));
        String[] names={"identityDocument","locationDocument","facilityImage"};
        for(int first=0;first<3;first++)for(int second=0;second<3;second++)if(first!=second)for(boolean nameFirst:new boolean[]{true,false}){
            var old=new java.util.LinkedHashMap<String,Object>();old.put("fullName",request.fullName().trim());old.put("email",request.email().trim().toLowerCase(java.util.Locale.ROOT));old.put("phone",request.phone());old.put("owner",signupJson.treeToValue(request.ownerApplication(),OwnerApplicationDtos.Create.class));old.put("setup",signupJson.treeToValue(request.ownerApplication().path("setup"),com.sporthub.common.dto.OwnerSignupSetup.class));
            for(int index:new int[]{first,second,3-first-second}){var file=new java.util.LinkedHashMap<String,Object>();for(String field:nameFirst?java.util.List.of("name","digest"):java.util.List.of("digest","name"))file.put(field,field.equals("name")?"fixture.png":fileDigest);old.put(names[index],file);}
            String legacy=java.util.HexFormat.of().formatHex(digest.digest(signupJson.valueToTree(old).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.update("UPDATE owner_signup_receipts SET request_hash=? WHERE user_id=?",legacy,initial.userId());
            assertThatThrownBy(()->signup(new RegisterRequest("Changed applicant",request.email(),null,request.password(),true,request.ownerApplication()),key)).isInstanceOf(IdentityException.class).hasMessageContaining("original registration");
            assertThat(jdbc.queryForObject("SELECT request_hash FROM owner_signup_receipts WHERE user_id=?",String.class,initial.userId())).isEqualTo(legacy);
            assertThat(signup(request,key).userId()).isEqualTo(initial.userId());
            assertThat(jdbc.queryForObject("SELECT request_hash FROM owner_signup_receipts WHERE user_id=?",String.class,initial.userId())).isEqualTo(canonical);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,initial.ownerApplication().id())).isEqualTo(1);
    }
    @Test void ownerSignupPreflightAndInvalidFilesCreateNothing(){
        var request=signupRequest(uniqueEmail("signup-invalid"));var file=signupFile();
        assertThatThrownBy(()->ownerSignup.register(request,UUID.randomUUID().toString(),new org.springframework.mock.web.MockMultipartFile("file","evil.txt","image/png","invalid".getBytes()),file,file,metadata)).isInstanceOf(IdentityException.class).hasMessageContaining("identityDocument");
        assertThatThrownBy(()->ownerSignup.register(request,UUID.randomUUID().toString(),new org.springframework.mock.web.MockMultipartFile("file","huge.pdf","application/pdf",new byte[10*1024*1024+1]),file,file,metadata)).isInstanceOf(IdentityException.class).hasMessageContaining("10 MB");
        assertThatThrownBy(()->signup(new RegisterRequest(request.fullName(),request.email(),null,request.password(),true,signupJson.createObjectNode()),UUID.randomUUID().toString())).isInstanceOf(IdentityException.class);
        org.mockito.Mockito.when(applicationDependencies.facility(any(),eq("signup-validate"),anyMap(),any())).thenThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.BAD_REQUEST));
        assertThatThrownBy(()->signup(request,UUID.randomUUID().toString())).isInstanceOf(IdentityException.class).hasMessageContaining("Invalid first facility");assertThat(userRepository.findByEmailIgnoreCase(request.email())).isEmpty();
    }
    @Test void ownerSignupDependencyFailureLeavesSafeDraftAndSameRetryCompletes(){
        var request=signupRequest(uniqueEmail("signup-downstream"));String key=UUID.randomUUID().toString();org.mockito.Mockito.when(applicationDependencies.facility(any(),eq("signup-prepare"),anyMap(),any())).thenThrow(new IllegalStateException("dependency offline"));
        var first=signup(request,key);assertThat(first.ownerApplication().state()).isEqualTo("DRAFT");assertThat(first.ownerSetupMessage()).contains("verify");assertThat(userRepository.findById(first.userId()).orElseThrow().getRoles()).isEmpty();
        org.mockito.Mockito.when(applicationDependencies.facility(any(),eq("signup-prepare"),anyMap(),any())).thenReturn(signupJson.createObjectNode());var repeat=signup(request,key);
        assertThat(repeat.userId()).isEqualTo(first.userId());assertThat(repeat.ownerApplication().state()).isEqualTo("PENDING_APPROVAL");assertThat(repeat.ownerSetupMessage()).isNull();
    }
    @Test void ownerSignupLockedReceiptCannotResumeAndBadAccountCreatesNothing(){
        var request=signupRequest(uniqueEmail("signup-lock"));String key=UUID.randomUUID().toString();var result=signup(request,key);
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",result.userId());assertThatThrownBy(()->signup(request,key)).isInstanceOf(IdentityException.class).hasMessageContaining("inactive");
        String invalidEmail=uniqueEmail("signup-account-invalid");var invalid=new RegisterRequest("A",invalidEmail,null,"short",true,request.ownerApplication());assertThatThrownBy(()->signup(invalid,UUID.randomUUID().toString())).isInstanceOf(IdentityException.class).hasMessageContaining("account information");assertThat(userRepository.findByEmailIgnoreCase(invalidEmail)).isEmpty();
    }
    @Test void ownerSignupMissingFacilityResumesInExistingAuthenticatedWorkspace()throws Exception{
        var request=signupRequest(uniqueEmail("signup-workspace"));
        org.mockito.Mockito.when(applicationDependencies.facility(any(),eq("create"),anyMap(),any())).thenThrow(new IllegalStateException("facility offline"));
        var registered=signup(request,UUID.randomUUID().toString());UUID id=registered.ownerApplication().id();UUID facility=registered.ownerApplication().facilityId();
        assertThat(registered.ownerApplication().state()).isEqualTo("DRAFT");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/owner-applications/"+id+"/initialize")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        authService.verify(new VerificationRequest(null,registered.verificationChallengeId(),verificationCodes.get(request.email())),metadata);
        var account=userRepository.findById(registered.userId()).orElseThrow();
        assertThatThrownBy(()->applications.initialize(id,applicant(activate(uniqueEmail("other-workspace"))))).isInstanceOf(IdentityException.class).hasMessageContaining("another applicant");
        org.mockito.Mockito.when(applicationDependencies.facility(any(),eq("create"),anyMap(),any())).thenReturn(signupJson.createObjectNode());
        var first=applications.initialize(id,applicant(account));var replay=applications.initialize(id,applicant(account));
        assertThat(first.id()).isEqualTo(id);assertThat(replay.facilityId()).isEqualTo(facility);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_applications WHERE user_id=?",Integer.class,account.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_signup_receipts WHERE user_id=?",Integer.class,account.getId())).isEqualTo(1);
        assertThat(account.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(applications.submit(id,applicant(account),null).state()).isEqualTo("PENDING_APPROVAL");
    }
    @Test void ownerSignupFalseIgnoresMalformedOwnerDataAndProducesOnlyOrdinaryAccount(){
        var request=new RegisterRequest("Ordinary applicant",uniqueEmail("signup-off"),null,"Password123!",false,signupJson.getNodeFactory().textNode("malformed ignored"));
        var result=ownerSignup.register(request,null,null,null,null,metadata);assertThat(result.ownerApplication()).isNull();assertThat(userRepository.findById(result.userId()).orElseThrow().getRoles()).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_applications WHERE user_id=?",Integer.class,result.userId())).isZero();org.mockito.Mockito.verifyNoInteractions(applicationDependencies);
    }
    @Test void ownerSignupTamperingCannotChooseUserOwnerStateOrGrantRole()throws Exception{
        var request=signupRequest(uniqueEmail("signup-tamper"));var body=(com.fasterxml.jackson.databind.node.ObjectNode)signupJson.valueToTree(request);body.put("role","OWNER");body.put("applicantUserId",UUID.randomUUID().toString());var owner=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("ownerApplication");owner.put("state","APPROVED");owner.put("userId",UUID.randomUUID().toString());((com.fasterxml.jackson.databind.node.ObjectNode)owner.path("facility")).put("ownerId",UUID.randomUUID().toString()).put("status","ACTIVE");
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/auth/register").file(new org.springframework.mock.web.MockMultipartFile("request","request.json","application/json",signupJson.writeValueAsBytes(body))).file(new org.springframework.mock.web.MockMultipartFile("identityDocument","identity.png","image/png",signupFile().getBytes())).file(new org.springframework.mock.web.MockMultipartFile("locationDocument","location.png","image/png",signupFile().getBytes())).file(new org.springframework.mock.web.MockMultipartFile("facilityImage","image.png","image/png",signupFile().getBytes())).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        var result=signupJson.readTree(response.getResponse().getContentAsString()).path("data");UUID user=UUID.fromString(result.path("userId").asText());assertThat(userRepository.findById(user).orElseThrow().getRoles()).isEmpty();assertThat(result.path("ownerApplication").path("state").asText()).isEqualTo("PENDING_APPROVAL");assertThat(result.path("ownerApplication").path("userId").asText()).isEqualTo(user.toString());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/owner-applications").contentType("application/json").content(signupJson.writeValueAsString(applicationInput()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/register").contentType("application/json").content(signupJson.writeValueAsString(request))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void ownerSignupRevisionResubmitAndRejectUseExistingLifecycle(){
        var request=signupRequest(uniqueEmail("signup-revision"));var result=signup(request,UUID.randomUUID().toString());var admin=applicationAdmin();UUID id=result.ownerApplication().id();
        applications.decide(id,new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED","Please provide a clearer location document",null),admin);authService.verify(new VerificationRequest(null,result.verificationChallengeId(),verificationCodes.get(request.email())),metadata);
        var user=userRepository.findById(result.userId()).orElseThrow();applications.save(id,applicationLegal(),applicant(user));assertThat(applications.submit(id,applicant(user),null).state()).isEqualTo("PENDING_APPROVAL");applications.decide(id,new OwnerApplicationDtos.Decision("REJECT","The supplied location document is not acceptable",null),admin);
        assertThat(userRepository.findById(result.userId()).orElseThrow().getRoles()).containsExactly(Role.CUSTOMER);assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,id)).isEqualTo(2);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID() + "@sporthub.local";
    }

    private String uniquePhone() {
        long digits = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        return "+84" + String.format("%09d", digits);
    }

    private com.sporthub.identity.security.AuthenticatedUser applicant(User user){return new com.sporthub.identity.security.AuthenticatedUser(user.getId(),user.getEmail(),java.util.Set.copyOf(user.getRoles()),UUID.randomUUID());}
    private com.sporthub.identity.security.AuthenticatedUser applicationAdmin(){var u=activate(uniqueEmail("application-admin"));u.getRoles().add(Role.ADMIN);userRepository.saveAndFlush(u);return applicant(u);}
    private OwnerApplicationDtos.Create applicationInput(){return new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility("First facility","+84901234567","Demo address","01","Hà Nội","00004","Ba Đình",null,"Asia/Ho_Chi_Minh",new java.math.BigDecimal("21.03"),new java.math.BigDecimal("105.78"),java.util.Set.of()));}
    @Test void administrativeAddressIsValidatedBeforeAccountOrApplicationWrites(){
        var request=signupRequest(uniqueEmail("signup-admin-address"));var payload=request.ownerApplication().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("facility")).put("provinceCode","79");
        assertThatThrownBy(()->signup(new RegisterRequest(request.fullName(),request.email(),null,request.password(),true,payload),UUID.randomUUID().toString())).isInstanceOf(IdentityException.class);assertThat(userRepository.findByEmailIgnoreCase(request.email())).isEmpty();org.mockito.Mockito.verifyNoInteractions(applicationDependencies);
        var user=activate(uniqueEmail("application-admin-address"));var actor=applicant(user);var f=applicationInput().facility();
        for(var pair:java.util.List.of(new String[]{"99","00004"},new String[]{"79","00004"},new String[]{"01","99999"})){
            var invalid=new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility(f.name(),f.phone(),f.addressLine(),pair[0],f.province(),pair[1],f.ward(),f.description(),f.timezone(),f.latitude(),f.longitude(),f.amenities()));
            assertThatThrownBy(()->applications.create(invalid,actor)).isInstanceOf(IdentityException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_applications WHERE user_id=?",Integer.class,user.getId())).isZero();
        var untrusted=new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility(f.name(),f.phone(),f.addressLine(),"01","Arbitrary province","00004","Arbitrary ward",f.description(),f.timezone(),f.latitude(),f.longitude(),f.amenities()));
        var saved=applications.create(untrusted,actor);assertThat(jdbc.queryForObject("SELECT initial_facility->>'province' FROM owner_applications WHERE id=?",String.class,saved.id())).isEqualTo("Hà Nội");assertThat(jdbc.queryForObject("SELECT initial_facility->>'ward' FROM owner_applications WHERE id=?",String.class,saved.id())).isEqualTo("Ba Đình");
    }
    private OwnerApplicationDtos.Legal applicationLegal(){return new OwnerApplicationDtos.Legal("Demo representative","TEST-IDENTITY-PRIVATE","Demo business",null,null,"Demo bank","Demo representative","TEST-BANK-PRIVATE");}
    private OwnerApplicationDtos.Decision approval(){return new OwnerApplicationDtos.Decision("APPROVE","Application reviewed and accepted",new java.math.BigDecimal("5.00"));}


    private OwnerApplicationDtos.Create contactApplicationInput(String email){
        var f=applicationInput().facility();return new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility(f.name(),f.phone(),f.addressLine(),f.provinceCode(),f.province(),f.wardCode(),f.ward(),f.description(),f.timezone(),f.latitude(),f.longitude(),f.amenities(),email));
    }
    private void mockContactSnapshot(UUID id,String email) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var snapshot=mapper.createObjectNode();snapshot.putObject("facility").put("name","First facility").put("contactEmail",email);
        org.mockito.Mockito.when(applicationDependencies.facility(eq(id),eq("submit"),anyMap(),any())).thenReturn(mapper.createObjectNode().set("snapshot",snapshot));
    }
    @Test void contactEmailApplicationValidatesNormalizesAndKeepsAccountCredentialSeparate() throws Exception {
        var user=activate(uniqueEmail("contact-email"));var actor=applicant(user);
        assertThatThrownBy(()->applications.create(contactApplicationInput("malformed"),actor)).isInstanceOf(IdentityException.class);
        var app=applications.create(contactApplicationInput("  Facility+Contact@EXAMPLE.TEST  "),actor);
        assertThat(jdbc.queryForObject("SELECT initial_facility->>'contactEmail' FROM owner_applications WHERE id=?",String.class,app.id())).isEqualTo("facility+contact@example.test");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
        mockContactSnapshot(app.id(),"facility+contact@example.test");applications.submit(app.id(),actor,null);
        assertThat(applications.detail(app.id(),applicationAdmin(),true).reviewSnapshot().path("facility").path("contactEmail").asText()).isEqualTo("facility+contact@example.test");
        org.mockito.Mockito.verify(applicationDependencies,org.mockito.Mockito.atLeastOnce()).facility(eq(app.id()),eq("create"),argThat(body->((com.fasterxml.jackson.databind.JsonNode)body.get("facility")).path("contactEmail").asText().equals("facility+contact@example.test")),any());
    }
    @Test void contactEmailRevisionKeepsSubmittedVersionsAndApprovalReadsImmutableSnapshot() throws Exception {
        var user=activate(uniqueEmail("contact-revision"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(contactApplicationInput("old@example.test"),actor);
        mockContactSnapshot(app.id(),"old@example.test");applications.submit(app.id(),actor,null);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var operational=mapper.readTree("{\"facility\":{\"name\":\"Operational\",\"contactEmail\":\"new@example.test\"}}");
        org.mockito.Mockito.when(applicationDependencies.facility(eq(app.id()),eq("view"),anyMap(),any())).thenReturn(operational);
        assertThat(applications.detail(app.id(),admin,true).reviewSnapshot().path("facility").path("contactEmail").asText()).isEqualTo("old@example.test");
        applications.decide(app.id(),new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED","Please correct the facility contact email",null),admin);
        mockContactSnapshot(app.id(),"new@example.test");applications.submit(app.id(),actor,null);applications.submit(app.id(),actor,null);
        var detail=applications.detail(app.id(),admin,true);assertThat(detail.history()).hasSize(2);assertThat(detail.reviewSnapshot().path("facility").path("contactEmail").asText()).isEqualTo("new@example.test");
        assertThat(detail.history()).anySatisfy(h->assertThat(((com.fasterxml.jackson.databind.JsonNode)h.get("facilitySnapshot")).path("facility").path("contactEmail").asText()).isEqualTo("old@example.test"));
        var history=applications.history(app.id(),0,20,admin);assertThat(history.items()).filteredOn(e->e.submissionOrigin()!=null&&e.submissionOrigin().equals("SUPPLEMENT_REQUIRED")).singleElement().satisfies(e->assertThat(e.changedFields()).containsExactly("contactEmail"));
        assertThat(mapper.findAndRegisterModules().writeValueAsString(history)).doesNotContain("old@example.test","new@example.test");
        applications.decide(app.id(),approval(),admin);applications.decide(app.id(),approval(),admin);
        ((com.fasterxml.jackson.databind.node.ObjectNode)operational.path("facility")).put("contactEmail","post-approval@example.test");
        var approved=applications.detail(app.id(),admin,true);assertThat(approved.facility().path("facility").path("contactEmail").asText()).isEqualTo("post-approval@example.test");assertThat(approved.reviewSnapshot().path("facility").path("contactEmail").asText()).isEqualTo("new@example.test");assertThat(approved.history()).hasSize(2);
    }
    @Test void locationOperationalEditAfterApprovalKeepsImmutableApplicationSubmission() throws Exception {
        var user=activate(uniqueEmail("location-snapshot"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var snapshot=mapper.readTree("{\"facility\":{\"name\":\"First facility\",\"latitude\":21,\"longitude\":105}}");
        org.mockito.Mockito.when(applicationDependencies.facility(eq(app.id()),eq("submit"),anyMap(),any())).thenReturn(mapper.createObjectNode().set("snapshot",snapshot));
        applications.submit(app.id(),actor,null);applications.decide(app.id(),approval(),admin);
        var operational=mapper.readTree("{\"facility\":{\"name\":\"First facility\",\"latitude\":22,\"longitude\":106}}");
        org.mockito.Mockito.when(applicationDependencies.facility(eq(app.id()),eq("view"),anyMap(),any())).thenReturn(operational);
        var detail=applications.detail(app.id(),admin,true);assertThat(detail.facility().path("facility").path("latitude").asInt()).isEqualTo(22);
        assertThat(detail.reviewSnapshot().path("facility").path("latitude").asInt()).isEqualTo(21);assertThat(detail.history()).singleElement().satisfies(h->assertThat(((com.fasterxml.jackson.databind.JsonNode)h.get("facilitySnapshot")).path("facility").path("longitude").asInt()).isEqualTo(105));
        assertThat(jdbc.queryForObject("SELECT facility_snapshot->'facility'->>'latitude' FROM owner_application_submissions WHERE application_id=?",String.class,app.id())).isEqualTo("21");
    }
    @Test void contactEmailLegacySnapshotsStayMissingAndNoChangeIsFabricated() throws Exception {
        var user=activate(uniqueEmail("contact-legacy"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        assertThat(applications.detail(app.id(),admin,true).reviewSnapshot()).isNull();
        applications.submit(app.id(),actor,null);assertThat(applications.detail(app.id(),admin,true).reviewSnapshot().path("facility").has("contactEmail")).isFalse();
        applications.decide(app.id(),new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED","Please complete legacy facility information",null),admin);
        mockContactSnapshot(app.id(),"introduced@example.test");applications.submit(app.id(),actor,null);
        assertThat(applications.history(app.id(),0,20,admin).items()).filteredOn(e->e.submissionOrigin()!=null).allSatisfy(e->assertThat(e.changedFields()).isEmpty());
        assertThat(applications.detail(app.id(),admin,true).history()).anySatisfy(h->assertThat(((com.fasterxml.jackson.databind.JsonNode)h.get("facilitySnapshot")).path("facility").has("contactEmail")).isFalse());
    }
    @Test void contactEmailUnchangedResubmitDoesNotMarkAFalseEdit() throws Exception {
        var user=activate(uniqueEmail("contact-unchanged"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(contactApplicationInput("same@example.test"),actor);mockContactSnapshot(app.id(),"same@example.test");applications.submit(app.id(),actor,null);
        applications.decide(app.id(),new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED","Please provide a clearer location document",null),admin);applications.submit(app.id(),actor,null);
        assertThat(applications.history(app.id(),0,20,admin).items()).filteredOn(e->e.submissionOrigin()!=null).allSatisfy(e->assertThat(e.changedFields()).isEmpty());
    }

    @Test void ownerApplicationApprovalIsIdempotentAuditedPrivateAndRevokesOldSessions() throws Exception {
        var user=activate(uniqueEmail("application"));var actor=applicant(user);var admin=applicationAdmin();
        var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);var oldPrincipal=jwtService.parseAccessToken(session.response().accessToken());
        var draft=applications.create(applicationInput(),actor);
        assertThat(jdbc.queryForObject("SELECT private_payload FROM owner_applications WHERE id=?",String.class,draft.id())).doesNotContain("TEST-IDENTITY-PRIVATE","TEST-BANK-PRIVATE");
        assertThatThrownBy(()->applications.detail(draft.id(),applicationAdmin(),false)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(()->applications.decide(draft.id(),approval(),actor)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(()->applications.decide(draft.id(),approval(),admin)).isInstanceOf(IdentityException.class);
        assertThat(applications.submit(draft.id(),actor,null).state()).isEqualTo("PENDING_APPROVAL");
        assertThat(applications.submit(draft.id(),actor,null).state()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,draft.id())).isEqualTo(1);
        assertThatThrownBy(()->applications.save(draft.id(),applicationLegal(),actor)).isInstanceOf(IdentityException.class);
        assertThat(applications.detail(draft.id(),admin,true).legal().identityNumber()).isEqualTo("TEST-IDENTITY-PRIVATE");
        assertThat(applications.committed(java.util.List.of(draft.id()))).isEmpty();
        assertThat(applications.decide(draft.id(),approval(),admin).state()).isEqualTo("APPROVED");
        assertThat(applications.decide(draft.id(),approval(),admin).state()).isEqualTo("APPROVED");
        applications.process(draft.id());
        assertThat(profileService.get(user.getId()).roles()).contains(Role.CUSTOMER,Role.OWNER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id=? AND role='OWNER'",Integer.class,user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='OWNER_APPLICATION_APPROVED'",Integer.class,draft.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE user_id=?",Integer.class,user.getId())).isEqualTo(1);
        assertThatThrownBy(()->sessionValidation.validate(oldPrincipal)).isInstanceOf(IllegalArgumentException.class);
        assertThat(authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata).response().user().roles()).contains(Role.OWNER);
        assertThat(applications.committed(java.util.List.of(draft.id(),draft.id()))).containsExactly(draft.id());
        org.mockito.Mockito.verify(applicationDependencies,org.mockito.Mockito.times(1)).wallet(draft.id(),user.getId(),new java.math.BigDecimal("5.00"));
        String audit=jdbc.queryForObject("SELECT new_value::text FROM audit_log WHERE entity_id=? AND action='OWNER_APPLICATION_APPROVED'",String.class,draft.id());
        assertThat(audit).doesNotContain("TEST-IDENTITY-PRIVATE","TEST-BANK-PRIVATE");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/owner-applications").header("Authorization","Bearer "+session.response().accessToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test void ownerApplicationSupplementResubmitAndRejectionPreserveSnapshotsAndNeverGrantOwner(){
        var user=activate(uniqueEmail("application-supplement"));var actor=applicant(user);var admin=applicationAdmin();var draft=applications.create(applicationInput(),actor);
        applications.submit(draft.id(),actor,null);
        assertThatThrownBy(()->applications.decide(draft.id(),new OwnerApplicationDtos.Decision("REJECT","short",null),admin)).isInstanceOf(IdentityException.class);
        var supplement=new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED","Please attach clearer identity and lease documents",null);
        assertThat(applications.decide(draft.id(),supplement,admin).state()).isEqualTo("SUPPLEMENT_REQUIRED");
        applications.save(draft.id(),new OwnerApplicationDtos.Legal("Updated","UPDATED-PRIVATE","Updated business",null,null,"Bank","Updated","UPDATED-BANK"),actor);
        applications.submit(draft.id(),actor,null);
        assertThat(applications.detail(draft.id(),admin,true).history()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,draft.id())).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("UPDATE owner_application_submissions SET private_snapshot='changed' WHERE application_id=?",draft.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var rejection=new OwnerApplicationDtos.Decision("REJECT","Application location documents are not valid",null);
        assertThat(applications.decide(draft.id(),rejection,admin).state()).isEqualTo("REJECTED");
        assertThat(applications.decide(draft.id(),rejection,admin).state()).isEqualTo("REJECTED");
        assertThatThrownBy(()->applications.submit(draft.id(),actor,null)).isInstanceOf(IdentityException.class);
        assertThat(profileService.get(user.getId()).roles()).containsExactly(Role.CUSTOMER);
        assertThat(applications.create(applicationInput(),actor).id()).isNotEqualTo(draft.id());
    }
    @Test void ownerApplicationConcurrentSubmitUsesOneLeaseAndRecoversAfterDependencyFailure() throws Exception {
        var user=activate(uniqueEmail("application-race"));var actor=applicant(user);var draft=applications.create(applicationInput(),actor);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(applicationDependencies.facility(org.mockito.ArgumentMatchers.eq(draft.id()),org.mockito.ArgumentMatchers.eq("submit"),anyMap(),any())).thenAnswer(invocation->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test timed out");return new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"snapshot\":{\"facility\":{\"name\":\"Race facility\"}}}");});
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var first=pool.submit(()->applications.submit(draft.id(),actor,null));assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(applications.submit(draft.id(),actor,null).state()).isEqualTo("SUBMITTING");release.countDown();assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("PENDING_APPROVAL");}finally{release.countDown();}
        org.mockito.Mockito.verify(applicationDependencies,org.mockito.Mockito.times(1)).facility(org.mockito.ArgumentMatchers.eq(draft.id()),org.mockito.ArgumentMatchers.eq("submit"),anyMap(),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE application_id=?",Integer.class,draft.id())).isEqualTo(1);
        var second=activate(uniqueEmail("application-retry"));var secondActor=applicant(second);var next=applications.create(applicationInput(),secondActor);
        org.mockito.Mockito.when(applicationDependencies.facility(org.mockito.ArgumentMatchers.eq(next.id()),org.mockito.ArgumentMatchers.eq("submit"),anyMap(),any())).thenThrow(new org.springframework.web.client.ResourceAccessException("Test dependency unavailable")).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"snapshot\":{\"facility\":{\"name\":\"Recovered\"}}}"));
        assertThat(applications.submit(next.id(),secondActor,null).state()).isEqualTo("SUBMITTING");
        jdbc.update("UPDATE owner_applications SET lease_until=NOW()-INTERVAL '1 second',lease_token=?,next_attempt_at=NOW() WHERE id=?",UUID.randomUUID(),next.id());
        applications.reconcile();assertThat(applications.own(secondActor).getFirst().state()).isEqualTo("PENDING_APPROVAL");
    }
    @Test void ownerApplicationLockedApplicantBlocksApprovalAndLockedDuringActivationCannotPublish(){
        var user=activate(uniqueEmail("application-lock"));var actor=applicant(user);var admin=applicationAdmin();var draft=applications.create(applicationInput(),actor);applications.submit(draft.id(),actor,null);
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",user.getId());
        assertThatThrownBy(()->applications.decide(draft.id(),approval(),admin)).isInstanceOf(IdentityException.class).hasMessageContaining("locked");
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?",user.getId());
        org.mockito.Mockito.when(applicationDependencies.wallet(draft.id(),user.getId(),new java.math.BigDecimal("5.00"))).thenAnswer(invocation->{jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",user.getId());return null;});
        assertThat(applications.decide(draft.id(),approval(),admin).state()).isEqualTo("APPROVING");
        assertThat(applications.committed(java.util.List.of(draft.id()))).isEmpty();assertThat(profileService.get(user.getId()).roles()).doesNotContain(Role.OWNER);
        org.mockito.Mockito.doReturn(null).when(applicationDependencies).wallet(draft.id(),user.getId(),new java.math.BigDecimal("5.00"));jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?",user.getId());
        assertThat(applications.retry(draft.id(),admin).state()).isEqualTo("APPROVED");
    }
    @Test void concurrentApprovalKeepsOneDecisionAndRejectsCompetingRejection() throws Exception {
        var user=activate(uniqueEmail("application-approval-race"));var actor=applicant(user);var firstAdmin=applicationAdmin();var otherAdmin=applicationAdmin();var draft=applications.create(applicationInput(),actor);applications.submit(draft.id(),actor,null);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(applicationDependencies.wallet(draft.id(),user.getId(),new java.math.BigDecimal("5.00"))).thenAnswer(invocation->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test timed out");return null;});
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->applications.decide(draft.id(),approval(),firstAdmin));assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(applications.decide(draft.id(),approval(),otherAdmin).state()).isEqualTo("APPROVING");
            assertThatThrownBy(()->applications.decide(draft.id(),new OwnerApplicationDtos.Decision("REJECT","A competing rejection must not overwrite approval",null),otherAdmin)).isInstanceOf(IdentityException.class);
            assertThat(applications.committed(java.util.List.of(draft.id()))).isEmpty();release.countDown();assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("APPROVED");
        }finally{release.countDown();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='OWNER_APPLICATION_DECISION_REQUESTED'",Integer.class,draft.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM owner_applications WHERE id=?",UUID.class,draft.id())).isEqualTo(firstAdmin.userId());
        org.mockito.Mockito.verify(applicationDependencies,org.mockito.Mockito.times(1)).wallet(draft.id(),user.getId(),new java.math.BigDecimal("5.00"));
    }
    @Test void applicationEncryptionRejectsMissingKeyTamperingAndDifferentApplication(){
        UUID id=UUID.randomUUID();String first=applicationCipher.seal("Private synthetic data",id),second=applicationCipher.seal("Private synthetic data",id);
        assertThat(first).isNotEqualTo(second);assertThat(applicationCipher.open(first,id)).isEqualTo("Private synthetic data");
        assertThatThrownBy(()->applicationCipher.open(first,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new OwnerApplicationCipher("").seal("Data",id)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(()->applicationCipher.open(first.substring(0,first.length()-4)+"AAAA",id)).isInstanceOf(IllegalStateException.class);
    }

    private OwnerApplicationDtos.SearchCriteria searchCriteria(Map<String,String> p){
        return new OwnerApplicationDtos.SearchCriteria(p.get("q"),p.get("state"),p.get("applicant"),p.get("facility"),
            p.get("submittedFrom"),p.get("submittedTo"),p.get("reviewedFrom"),p.get("reviewedTo"),p.get("reviewedBy"),p.get("applicationId"),p.get("sort"),p.get("page"),p.get("size"));
    }
    private record SearchFixture(UUID id,String email,String phone,String name){}
    private SearchFixture searchFixture(String tag,String facility,String state,String submitted,String reviewed,UUID reviewer){
        var user=activate(uniqueEmail("filter"));UUID id=UUID.randomUUID();String name=tag+" applicant";
        String phone="+84"+Long.toUnsignedString(System.nanoTime());
        jdbc.update("UPDATE user_profiles SET full_name=? WHERE user_id=?",name,user.getId());
        jdbc.update("UPDATE users SET phone=? WHERE id=?",phone,user.getId());
        jdbc.update("INSERT INTO owner_applications(id,user_id,facility_id,state,business_name,facility_name,private_payload,initial_facility,submitted_at,reviewed_at,reviewed_by,created_at) VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?::timestamptz)",
            id,user.getId(),UUID.randomUUID(),state,tag,facility,applicationCipher.seal("{\"representativeName\":\"ENCRYPTED-NAME-ONLY\",\"identityNumber\":\"DO-NOT-SEARCH-ME\"}",id),"{}",
            submitted==null?null:java.sql.Timestamp.from(Instant.parse(submitted)),reviewed==null?null:java.sql.Timestamp.from(Instant.parse(reviewed)),reviewer,"2026-01-01T00:00:00Z");
        return new SearchFixture(id,user.getEmail(),phone,name);
    }
    private java.util.List<UUID> filteredIds(Map<String,String> p,com.sporthub.identity.security.AuthenticatedUser admin){
        return applications.search(searchCriteria(p),admin).items().stream().map(OwnerApplicationDtos.Summary::id).toList();
    }

    @Test void ownerHistoryRevisionTimelinePreservesReasonsActorsTransitionsAndSubmissionLinks(){
        var user=activate(uniqueEmail("history-revision"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        applications.submit(app.id(),actor,null);
        String reason="Please provide clearer verification documents for review";
        applications.decide(app.id(),new OwnerApplicationDtos.Decision("SUPPLEMENT_REQUIRED",reason,null),admin);
        var legal=applicationLegal();applications.save(app.id(),new OwnerApplicationDtos.Legal(legal.representativeName(),"HISTORY-PRIVATE-IDENTITY",legal.businessName(),legal.businessLicense(),legal.taxCode(),legal.bankName(),legal.bankAccountHolder(),"HISTORY-PRIVATE-BANK"),actor);
        applications.submit(app.id(),actor,null);applications.decide(app.id(),approval(),admin);applications.decide(app.id(),approval(),admin);
        var history=applications.history(app.id(),0,100,admin);
        assertThat(history.items()).hasSize(8);
        assertThat(history.items()).allSatisfy(e->assertThat(e.metadataAvailable()).isTrue());
        var revision=history.items().stream().filter(e->e.action().equals("OWNER_APPLICATION_SUPPLEMENT_REQUIRED")).findFirst().orElseThrow();
        assertThat(revision.actorId()).isEqualTo(admin.userId());assertThat(revision.actorName()).isNotBlank();assertThat(revision.reason()).isEqualTo(reason);assertThat(revision.fromState()).isEqualTo("DECIDING");assertThat(revision.toState()).isEqualTo("SUPPLEMENT_REQUIRED");
        var updated=history.items().stream().filter(e->e.action().equals("OWNER_APPLICATION_UPDATED")).findFirst().orElseThrow();
        assertThat(updated.changedFields()).containsExactly("identityNumber","bankAccountNumber");
        var submitted=history.items().stream().filter(e->e.action().equals("OWNER_APPLICATION_SUBMITTED")).toList();assertThat(submitted).hasSize(2);
        assertThat(submitted).allSatisfy(e->assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_application_submissions WHERE id=? AND application_id=?",Integer.class,e.submissionId(),app.id())).isEqualTo(1));
        assertThat(submitted).extracting(OwnerApplicationDtos.HistoryEntry::submissionOrigin).containsExactlyInAnyOrder("DRAFT","SUPPLEMENT_REQUIRED");
        assertThat(history.items().stream().filter(e->e.action().equals("OWNER_APPLICATION_APPROVED"))).hasSize(1);
        String stored=jdbc.queryForObject("SELECT string_agg(coalesce(old_value::text,'') || new_value::text,' ') FROM audit_log WHERE entity_id=?",String.class,app.id());
        assertThat(stored).doesNotContain("HISTORY-PRIVATE-IDENTITY","HISTORY-PRIVATE-BANK","TEST-IDENTITY-PRIVATE","TEST-BANK-PRIVATE");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(history).toString()).doesNotContain("private_payload","private_snapshot","HISTORY-PRIVATE-IDENTITY","HISTORY-PRIVATE-BANK");
    }
    @Test void ownerHistoryUnchangedLegalSaveDoesNotCreateFalseRevision(){
        var user=activate(uniqueEmail("history-noop"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        String encrypted=jdbc.queryForObject("SELECT private_payload FROM owner_applications WHERE id=?",String.class,app.id());
        applications.save(app.id(),applicationLegal(),actor);
        assertThat(jdbc.queryForObject("SELECT private_payload FROM owner_applications WHERE id=?",String.class,app.id())).isEqualTo(encrypted);
        assertThat(applications.history(app.id(),0,20,admin).items()).extracting(OwnerApplicationDtos.HistoryEntry::action).containsExactly("OWNER_APPLICATION_CREATED");
    }
    @Test void ownerHistoryPagingIsDeterministicAndViewAuditsCannotHideEdits(){
        var user=activate(uniqueEmail("history-pages"));var admin=applicationAdmin();var app=applications.create(applicationInput(),applicant(user));
        java.util.List<UUID> ids=new java.util.ArrayList<>();long auditPrefix=UUID.randomUUID().getMostSignificantBits();
        for(int i=0;i<105;i++){var id=new UUID(auditPrefix,i+1);ids.add(id);jdbc.update("INSERT INTO audit_log(id,user_id,action,entity_type,entity_id,new_value,created_at) VALUES(?,?,'OWNER_APPLICATION_UPDATED','OWNER_APPLICATION',?,'{}'::jsonb,'2020-01-01T00:00:00Z')",id,user.getId(),app.id());}
        for(int i=0;i<110;i++)jdbc.update("INSERT INTO audit_log(id,user_id,action,entity_type,entity_id,new_value) VALUES(?,?,'ADMIN_OWNER_APPLICATION_VIEWED','OWNER_APPLICATION',?,'{}'::jsonb)",UUID.randomUUID(),admin.userId(),app.id());
        var first=applications.history(app.id(),0,100,admin);var second=applications.history(app.id(),1,100,admin);
        assertThat(first.totalElements()).isEqualTo(106);assertThat(first.totalPages()).isEqualTo(2);assertThat(second.items()).hasSize(6);
        assertThat(first.items().get(1).id()).isEqualTo(ids.getLast());assertThat(second.items().getLast().id()).isEqualTo(ids.getFirst());
        assertThat(first.items()).extracting(OwnerApplicationDtos.HistoryEntry::id).isEqualTo(applications.history(app.id(),0,100,admin).items().stream().map(OwnerApplicationDtos.HistoryEntry::id).toList());
        assertThat(second.items()).allSatisfy(e->{assertThat(e.metadataAvailable()).isFalse();assertThat(e.fromState()).isNull();assertThat(e.toState()).isNull();});
        assertThat(applications.history(app.id(),2,100,admin).items()).isEmpty();
    }
    @Test void ownerHistoryLegacyProjectionDoesNotInventMetadataOrReturnArbitraryPayload(){
        var user=activate(uniqueEmail("history-legacy"));var admin=applicationAdmin();var app=applications.create(applicationInput(),applicant(user));
        jdbc.update("INSERT INTO audit_log(id,user_id,action,entity_type,entity_id,new_value) VALUES(?,?,'OWNER_APPLICATION_UPDATED','OWNER_APPLICATION',?,?::jsonb)",UUID.randomUUID(),user.getId(),app.id(),"{\"identityNumber\":\"LEGACY-SECRET\",\"changedFields\":[\"identityNumber\",\"password\"],\"state\":\"UNKNOWN\",\"submissionId\":\"invalid\"}");
        var e=applications.history(app.id(),0,20,admin).items().getFirst();assertThat(e.metadataAvailable()).isFalse();assertThat(e.toState()).isNull();assertThat(e.submissionId()).isNull();assertThat(e.changedFields()).containsExactly("identityNumber");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(e).toString()).doesNotContain("LEGACY-SECRET","password");
    }
    @Test void ownerHistoryAuthorizationPaginationValidationAndMissingApplication() throws Exception {
        var user=activate(uniqueEmail("history-guard"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        assertThatThrownBy(()->applications.history(app.id(),0,20,actor)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->applications.history(UUID.randomUUID(),0,20,admin)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        String adminToken=authService.login(new LoginRequest(admin.email(),"Password123!"),metadata).response().accessToken();
        String path="/api/v1/admin/owner-applications/"+app.id()+"/history";
        for(var p:java.util.List.of(Map.of("page","-1"),Map.of("size","0"),Map.of("size","101"),Map.of("page","invalid"))){var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+adminToken);p.forEach(req::param);http.perform(req).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());}
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+adminToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
        String customerToken=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+customerToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test void ownerHistoryDetailAccountSummaryIsAdminOnlyAndReadDoesNotGrantOwner() throws Exception {
        var user=activate(uniqueEmail("history-account"));var actor=applicant(user);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);
        var d=applications.detail(app.id(),admin,true);assertThat(d.applicant().id()).isEqualTo(user.getId());assertThat(d.applicant().email()).isEqualTo(user.getEmail());assertThat(d.applicant().status()).isEqualTo("ACTIVE");
        assertThat(applications.detail(app.id(),actor,false).applicant()).isNull();
        applications.history(app.id(),0,20,admin);assertThat(profileService.get(user.getId()).roles()).containsExactly(Role.CUSTOMER);assertThat(applications.own(actor).getFirst().state()).isEqualTo("DRAFT");
        String token=authService.login(new LoginRequest(admin.email(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/owner-applications/"+app.id()).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.applicant.email").value(user.getEmail()));
    }
    @Test void ownerSearchWithoutFiltersPreservesLegacyArrayOrderAndSummaryProjection(){
        var admin=applicationAdmin();var legacy=applications.search(null,null,admin);var page=applications.search(searchCriteria(Map.of("size","100")),admin);
        assertThat(page.items()).isEqualTo(legacy);
        assertThat(page.totalElements()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM owner_applications a JOIN users u ON u.id=a.user_id JOIN user_profiles p ON p.user_id=u.id",Long.class));
        assertThat(page.page()).isZero();assertThat(page.size()).isEqualTo(100);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(page).toString())
            .doesNotContain("private_payload","identityNumber","bankAccountNumber","initial_facility","ENCRYPTED-NAME-ONLY");
    }
    @Test void ownerSearchCombinesStateKeywordApplicantAndFacilityWithAnd(){
        var admin=applicationAdmin();String tag="combined-"+UUID.randomUUID();
        var a=searchFixture(tag,tag+" stadium","PENDING_APPROVAL","2026-10-06T01:00:00Z",null,null);
        searchFixture(tag,tag+" arena","REJECTED","2026-10-06T02:00:00Z",null,null);
        assertThat(filteredIds(Map.of("q",tag,"state","PENDING_APPROVAL"),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("q","  "+tag.toUpperCase()+"  ","facility","  STADIUM  ","applicant",a.email().toUpperCase(),"state","PENDING_APPROVAL","submittedFrom","2026-10-06","submittedTo","2026-10-06"),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("q",tag,"state","REJECTED","facility","stadium"),admin)).isEmpty();
    }
    @Test void ownerSearchApplicantMatchesOnlyExistingAccountNameEmailAndPhone(){
        var admin=applicationAdmin();String tag="contact-"+UUID.randomUUID();var a=searchFixture(tag,tag,"PENDING_APPROVAL",null,null,null);
        for(String keyword:java.util.List.of(a.email(),a.phone(),a.name()))assertThat(filteredIds(Map.of("applicant",keyword),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("q",tag,"applicant","ENCRYPTED-NAME-ONLY"),admin)).isEmpty();
        assertThat(filteredIds(Map.of("q",tag,"applicant","DO-NOT-SEARCH-ME"),admin)).isEmpty();
    }
    @Test void ownerSearchEscapesLiteralWildcardsAndUsesBoundParameters(){
        var admin=applicationAdmin();String tag="literal-"+UUID.randomUUID();var a=searchFixture(tag+" %_!\\",tag+" %_!\\","DRAFT",null,null,null);
        searchFixture(tag+" other",tag+" other","DRAFT",null,null,null);
        assertThat(filteredIds(Map.of("q",tag,"facility","%_!\\"),admin)).containsExactly(a.id());
        assertThat(applications.search(tag+" %_!\\",null,admin).stream().map(OwnerApplicationDtos.Summary::id)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("q","' OR 1=1 --"),admin)).isEmpty();
    }
    @Test void ownerSearchSubmittedFromIsInclusiveAtVietnamMidnight(){
        var admin=applicationAdmin();String tag="from-"+UUID.randomUUID();
        searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-05T16:59:59.999Z",null,null);
        var a=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-05T17:00:00Z",null,null);
        var b=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T17:00:00Z",null,null);
        searchFixture(tag,tag,"DRAFT",null,null,null);
        assertThat(filteredIds(Map.of("q",tag,"submittedFrom","2026-10-06"),admin)).containsExactly(a.id(),b.id());
    }
    @Test void ownerSearchSubmittedToIncludesWholeVietnamDayButExcludesNextMidnight(){
        var admin=applicationAdmin();String tag="to-"+UUID.randomUUID();
        var a=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-05T16:59:59Z",null,null);
        var b=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T16:59:59.999Z",null,null);
        searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T17:00:00Z",null,null);
        assertThat(filteredIds(Map.of("q",tag,"submittedTo","2026-10-06"),admin)).containsExactly(a.id(),b.id());
        assertThat(filteredIds(Map.of("q",tag,"submittedFrom","2026-10-06","submittedTo","2026-10-06"),admin)).containsExactly(b.id());
    }
    @Test void ownerSearchReviewerReviewDatesAndApplicationIdAreIndependentAndComposable(){
        var admin=applicationAdmin();String tag="review-"+UUID.randomUUID();
        var a=searchFixture(tag,tag,"REJECTED","2026-10-04T01:00:00Z","2026-10-05T17:00:00Z",admin.userId());
        searchFixture(tag,tag,"SUPPLEMENT_REQUIRED","2026-10-04T01:00:00Z","2026-10-06T17:00:00Z",admin.userId());
        searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-04T01:00:00Z",null,null);
        assertThat(filteredIds(Map.of("q",tag,"reviewedFrom","2026-10-06","reviewedTo","2026-10-06"),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("reviewedBy",admin.userId().toString(),"applicationId",a.id().toString()),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("applicationId",a.id().toString(),"reviewedBy",UUID.randomUUID().toString()),admin)).isEmpty();
        assertThat(filteredIds(Map.of("applicationId",a.id().toString()),admin)).containsExactly(a.id());
        assertThat(filteredIds(Map.of("q",tag,"reviewedFrom","2026-10-07"),admin)).hasSize(1);
        assertThat(filteredIds(Map.of("q",tag,"reviewedTo","2026-10-06"),admin)).containsExactly(a.id());
    }
    @Test void ownerSearchPaginationIsStableWithTiedDatesAndRetainsAllFilters(){
        var admin=applicationAdmin();String tag="pages-"+UUID.randomUUID();
        var a=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T01:00:00Z",null,null);
        var b=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T01:00:00Z",null,null);
        var c=searchFixture(tag,tag,"PENDING_APPROVAL","2026-10-06T01:00:00Z",null,null);
        var expected=java.util.stream.Stream.of(a.id(),b.id(),c.id()).sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        var filters=new java.util.HashMap<>(Map.of("q",tag,"state","PENDING_APPROVAL","submittedFrom","2026-10-06","size","2"));
        var first=applications.search(searchCriteria(filters),admin);assertThat(first.totalElements()).isEqualTo(3);assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items().stream().map(OwnerApplicationDtos.Summary::id)).containsExactlyElementsOf(expected.subList(0,2));
        filters.put("page","1");assertThat(filteredIds(filters,admin)).containsExactly(expected.get(2));
        filters.put("page","0");filters.put("sort","SUBMITTED_DESC");assertThat(filteredIds(filters,admin)).containsExactly(expected.get(2),expected.get(1));
        filters.put("page","2147483647");var outside=applications.search(searchCriteria(filters),admin);assertThat(outside.items()).isEmpty();assertThat(outside.totalElements()).isEqualTo(3);
    }
    @Test void ownerSearchBlankFiltersAreAbsentAndNoResultsHaveZeroPages(){
        var admin=applicationAdmin();String tag="blank-"+UUID.randomUUID();var a=searchFixture(tag,tag,"DRAFT",null,null,null);
        assertThat(filteredIds(Map.of("q","  "+tag+"  ","state","  ","facility","  ","submittedFrom"," "),admin)).containsExactly(a.id());
        var result=applications.search(searchCriteria(Map.of("q",UUID.randomUUID().toString())),admin);
        assertThat(result.items()).isEmpty();assertThat(result.totalElements()).isZero();assertThat(result.totalPages()).isZero();
    }
    @Test void ownerSearchRejectsInvalidDatesRangesEnumsUuidsSortAndPagination(){
        var admin=applicationAdmin();
        for(var p:java.util.List.of(Map.of("state","APPROVE"),Map.of("submittedFrom","2026-02-30"),Map.of("submittedTo","06/10/2026"),
            Map.of("submittedFrom","2026-10-07","submittedTo","2026-10-06"),Map.of("reviewedFrom","2026-10-07","reviewedTo","2026-10-06"),
            Map.of("applicationId","1-1-1-1-1"),Map.of("reviewedBy","invalid"),Map.of("sort","private_payload"),Map.of("page","-1"),
            Map.of("page","2147483648"),Map.of("page","abc"),Map.of("size","0"),Map.of("size","101"),Map.of("q","x".repeat(181)),Map.of("applicant","x".repeat(255)))) {
            assertThatThrownBy(()->applications.search(searchCriteria(p),admin)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        }
    }
    @Test void ownerSearchHttpRetainsLegacyContractAndReturnsStructuredValidation() throws Exception {
        var admin=applicationAdmin();String token=authService.login(new LoginRequest(admin.email(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/owner-applications").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isArray());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/owner-applications/page").param("size","2").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items").isArray())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.size").value(2)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
        for(var p:java.util.List.of(Map.of("state","INVALID"),Map.of("submittedFrom","2026-10-07","submittedTo","2026-10-06"),Map.of("sort","created_at"),Map.of("size","abc"))){
            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/owner-applications/page").header("Authorization","Bearer "+token);p.forEach(request::param);
            http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDENTITY-OWNER-APPLICATION"));
        }
    }
    @Test void ownerSearchRequiresRealAdminSessionAndLockedAdminCannotRead() throws Exception {
        var customer=activate(uniqueEmail("search-denied"));var customerActor=applicant(customer);
        assertThatThrownBy(()->applications.search(searchCriteria(Map.of()),customerActor)).isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        String path="/api/v1/admin/owner-applications/page";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token=authService.login(new LoginRequest(customer.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+token).header("X-User-Roles","ADMIN"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var admin=applicationAdmin();String adminToken=authService.login(new LoginRequest(admin.email(),"Password123!"),metadata).response().accessToken();
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",admin.userId());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+adminToken))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }


    @Test void ownerRoleCannotBeGrantedBeforeApplicationApprovalAndCustomerCannotCallAdmin() throws Exception {
        var admin=applicationAdmin();var target=activate(uniqueEmail("owner-escalation"));
        assertThatThrownBy(()->adminAccounts.roles(target.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER,Role.OWNER),"Unauthorized shortcut"),admin,metadata))
            .isInstanceOf(IdentityException.class).hasMessageContaining("approved Owner application");
        assertThatThrownBy(()->adminAccounts.roles(target.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER,Role.ADMIN),"Unapproved Admin delegation"),admin,metadata))
            .isInstanceOf(IdentityException.class).hasMessageContaining("hierarchy policy");
        assertThat(userRepository.findById(target.getId()).orElseThrow().getRoles()).containsExactly(Role.CUSTOMER);
        String token=authService.login(new LoginRequest(target.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/accounts/"+admin.userId()).header("Authorization","Bearer "+token).header("X-User-Role","ADMIN"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/staff-bindings/facilities/"+UUID.randomUUID()).header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test void parallelRefreshHasOneWinnerAndLosingRequestsNeverOverwriteItsCookie() throws Exception {
        var user=activate(uniqueEmail("parallel-refresh"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        String expired=io.jsonwebtoken.Jwts.builder().subject(user.getId().toString()).claim("sid",jwtService.parseAccessToken(session.response().accessToken()).sessionId().toString()).claim("roles",java.util.List.of("CUSTOMER"))
            .expiration(java.util.Date.from(Instant.now().minusSeconds(60))).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor("test-only-secret-that-is-at-least-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        var ready=new java.util.concurrent.CountDownLatch(8);var go=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{ready.countDown();go.await();return http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/refresh")
                .header("Authorization","Bearer "+expired).cookie(new jakarta.servlet.http.Cookie("sporthub_refresh",session.refreshToken()))).andReturn().getResponse();}));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();
            int success=0;String winner=null;
            for(var future:futures){var r=future.get(20,java.util.concurrent.TimeUnit.SECONDS);if(r.getStatus()==200){success++;winner=org.springframework.mock.web.MockCookie.parse(r.getHeader("Set-Cookie")).getValue();assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");}else{assertThat(r.getStatus()).isEqualTo(401);assertThat(r.getHeader("Set-Cookie")).isNull();}}
            assertThat(success).isEqualTo(1);assertThat(winner).isNotNull().isNotEqualTo(session.refreshToken());
            assertThatThrownBy(()->authService.refresh(session.refreshToken(),metadata)).isInstanceOf(IdentityException.class);
            assertThat(authService.refresh(winner,metadata).response().user().id()).isEqualTo(user.getId());
        } finally {go.countDown();pool.shutdownNow();}
    }
    @Test void lockedExpiredRevokedAndStaleCookieSessionsFailClosed() throws Exception {
        var user=activate(uniqueEmail("auth-security"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        var principal=jwtService.parseAccessToken(session.response().accessToken());
        String expired=io.jsonwebtoken.Jwts.builder().subject(user.getId().toString()).claim("sid",principal.sessionId().toString()).claim("roles",java.util.List.of("ADMIN"))
            .expiration(java.util.Date.from(Instant.now().minusSeconds(60))).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor("test-only-secret-that-is-at-least-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+expired)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout").header("Authorization","Bearer "+session.response().accessToken()).cookie(new jakarta.servlet.http.Cookie("sporthub_refresh","invalid-stale-cookie")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertThatThrownBy(()->sessionValidation.validate(principal)).isInstanceOf(IllegalArgumentException.class);
        var next=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",user.getId());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+next.response().accessToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThatThrownBy(()->authService.refresh(next.refreshToken(),metadata)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(()->authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata)).isInstanceOf(IdentityException.class);
    }
    @Test void authCookieAndResponseArePrivateAndUntrustedBrowserOriginsCannotRotate() throws Exception {
        var user=activate(uniqueEmail("origin-cookie"));
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login").header("Origin","http://localhost:3000").contentType("application/json")
            .content("{\"identifier\":\""+user.getEmail()+"\",\"password\":\"Password123!\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        String cookie=result.getResponse().getHeader("Set-Cookie");assertThat(cookie).contains("HttpOnly","SameSite=Lax","Path=/api/v1/auth");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken","passwordHash","codeHash","tokenHash");
        var refresh=org.springframework.mock.web.MockCookie.parse(result.getResponse().getHeader("Set-Cookie"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/refresh").header("Origin","https://attacker.invalid").cookie(refresh))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(authService.refresh(refresh.getValue(),metadata).response().user().id()).isEqualTo(user.getId());
    }
    @Test void removedStaffRoleAndRevokedBindingAreNotReturnedInAccessContext() throws Exception {
        var user=activate(uniqueEmail("binding-scope"));user.getRoles().add(Role.STAFF);userRepository.saveAndFlush(user);
        UUID facility=UUID.randomUUID(),binding=UUID.randomUUID();jdbc.update("INSERT INTO staff_facility_bindings(id,staff_user_id,facility_id,assigned_by) VALUES(?,?,?,?)",binding,user.getId(),facility,user.getId());
        jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,'BOOKING_READ')",binding);
        var token=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].facilityId").value(facility.toString()));
        jdbc.update("UPDATE staff_facility_bindings SET is_active=false WHERE id=?",binding);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings").isEmpty());
        jdbc.update("UPDATE staff_facility_bindings SET is_active=true WHERE id=?",binding);jdbc.update("DELETE FROM user_roles WHERE user_id=? AND role='STAFF'",user.getId());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings").isEmpty());
    }


    @Autowired org.springframework.transaction.PlatformTransactionManager securityTransactions;
    @Test void passwordResetCommitPreventsConcurrentLoginWithTheOldPassword() throws Exception {
        var user=activate(uniqueEmail("reset-login-race"));authService.forgotPassword(new ForgotPasswordRequest(user.getEmail()),metadata);
        var challenge=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();
        var locked=new java.util.concurrent.CountDownLatch(1);var commit=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reset=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(securityTransactions).execute(tx->{authService.resetPassword(new ResetPasswordRequest(challenge.getId(),resetCodes.get(user.getEmail()),"Replacement123!"),metadata);locked.countDown();try{if(!commit.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timed out");}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var login=pool.submit(()->{try{authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);return true;}catch(IdentityException denied){return false;}});
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND database=(SELECT oid FROM pg_database WHERE datname=current_database())",Integer.class)>0);
            commit.countDown();assertThat(reset.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(login.get(20,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(authService.login(new LoginRequest(user.getEmail(),"Replacement123!"),metadata).response().user().id()).isEqualTo(user.getId());
        }finally{commit.countDown();pool.shutdownNow();}
    }
    @Test void secureCookieConfigurationIsRespectedWithoutExposingRefreshTokenInJson() {
        var user=activate(uniqueEmail("secure-cookie"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        var auth=org.mockito.Mockito.mock(AuthService.class);org.mockito.Mockito.when(auth.login(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(session);
        var controller=new com.sporthub.identity.web.AuthController(auth,org.mockito.Mockito.mock(OwnerSignupService.class),new com.sporthub.identity.config.IdentityProperties(15,15,"https://sports.test","noreply@sports.test",true));
        var response=controller.login(new LoginRequest(user.getEmail(),"Password123!"),new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("Secure","HttpOnly","SameSite=Lax","Path=/api/v1/auth");
        assertThat(session.response().user().roles()).containsExactly(Role.CUSTOMER);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash()).startsWith("$2").isNotEqualTo("Password123!");
    }

    @Test void signedRoleClaimsCannotEscalateAndMissingExpiryOrMismatchedSessionIsRejected() throws Exception {
        var user=activate(uniqueEmail("jwt-claims"));var other=activate(uniqueEmail("jwt-other"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        String sid=jwtService.parseAccessToken(session.response().accessToken()).sessionId().toString();
        var key=io.jsonwebtoken.security.Keys.hmacShaKeyFor("test-only-secret-that-is-at-least-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String escalated=io.jsonwebtoken.Jwts.builder().subject(user.getId().toString()).claim("sid",sid).claim("roles",java.util.List.of("ADMIN"))
            .expiration(java.util.Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/accounts").header("Authorization","Bearer "+escalated)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String missingExpiry=io.jsonwebtoken.Jwts.builder().subject(user.getId().toString()).claim("sid",sid).claim("roles",java.util.List.of("CUSTOMER")).signWith(key).compact();
        String mismatched=io.jsonwebtoken.Jwts.builder().subject(other.getId().toString()).claim("sid",sid).claim("roles",java.util.List.of("CUSTOMER"))
            .expiration(java.util.Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        for(String token:java.util.List.of(missingExpiry,mismatched)) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        }
        jdbc.update("UPDATE refresh_tokens SET expires_at=NOW()-interval '1 second' WHERE id=?",java.util.UUID.fromString(sid));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("sporthub_refresh",session.refreshToken())))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Set-Cookie"));
    }
    @Test void loginThrottleIsEnforcedBeforeCredentialsAndExistingApiErrorShapeIsPreserved() throws Exception {
        org.mockito.Mockito.doThrow(new IdentityException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"IDENTITY-LOGIN-THROTTLED","Too many sign-in attempts. Please try again later"))
            .when(loginAttempts).reserve(org.mockito.ArgumentMatchers.eq("throttled@test.invalid"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login").contentType("application/json")
            .content("{\"identifier\":\"throttled@test.invalid\",\"password\":\"Password123!\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("IDENTITY-LOGIN-THROTTLED"));
        org.mockito.Mockito.reset(loginAttempts);
    }


    @Test void concurrentProfileChangeCannotRestoreActiveStatusAfterAdminLock() throws Exception {
        var user=activate(uniqueEmail("lock-profile-race"));var admin=applicationAdmin();var locked=new java.util.concurrent.CountDownLatch(1);var commit=new java.util.concurrent.CountDownLatch(1);var attempted=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var lock=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(securityTransactions).execute(tx->{adminAccounts.lock(user.getId(),new AdminAccountDtos.LockInput("Security test lock","PERMANENT"),admin,metadata);locked.countDown();try{if(!commit.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timed out");}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var profile=pool.submit(()->{attempted.countDown();try{profileService.update(user.getId(),new UpdateProfileRequest(null,uniqueEmail("blocked-contact"),null,null,null),metadata);return true;}catch(IdentityException denied){assertThat(denied.getStatus().value()).isEqualTo(403);return false;}});
            assertThat(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND database=(SELECT oid FROM pg_database WHERE datname=current_database())",Integer.class)>0);
            commit.countDown();
            assertThat(lock.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(profile.get(20,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.LOCKED);
        }finally{commit.countDown();pool.shutdownNow();}
    }

    @Test void cookieOnlyLogoutIsAuditedAndRevokesTheUnderlyingAccessSession() {
        var user=activate(uniqueEmail("cookie-only-audit"));var session=authService.login(new LoginRequest(user.getEmail(),"Password123!"),metadata);
        authService.logout(session.refreshToken(),null,null,metadata);
        assertThatThrownBy(()->sessionValidation.validate(jwtService.parseAccessToken(session.response().accessToken()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE user_id=? AND action='LOGOUT' AND entity_type='USER' AND entity_id=?",Integer.class,user.getId(),user.getId())).isEqualTo(1);
    }

    @Test void passwordResetCodeForReplacedContactCannotChangeTheAccountPassword() {
        var user=activate(uniqueEmail("reset-old-contact"));String originalHash=user.getPasswordHash();
        authService.forgotPassword(new ForgotPasswordRequest(user.getEmail()),metadata);
        var oldReset=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();String oldCode=resetCodes.get(user.getEmail());
        String replacement=uniqueEmail("reset-new-contact");profileService.update(user.getId(),new UpdateProfileRequest(null,replacement,null,null,null),metadata);
        var change=profileService.contactChallenges(user.getId()).getFirst();authService.verify(new VerificationRequest(null,change.id(),verificationCodes.get(replacement)),metadata);
        assertThatThrownBy(()->authService.resetPassword(new ResetPasswordRequest(oldReset.getId(),oldCode,"Replacement123!"),metadata)).isInstanceOf(IdentityException.class);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        authService.forgotPassword(new ForgotPasswordRequest(replacement),metadata);
        var current=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();
        authService.resetPassword(new ResetPasswordRequest(current.getId(),resetCodes.get(replacement),"Replacement123!"),metadata);
        assertThat(authService.login(new LoginRequest(replacement,"Replacement123!"),metadata).response().user().id()).isEqualTo(user.getId());
    }

    @Test void replacedPhoneInvalidatesItsResetCodeAndLockedAccountCannotVerifyContactChange() {
        var user=activate(uniqueEmail("reset-phone-contact"));String oldPhone=uniquePhone(),newPhone=uniquePhone();
        profileService.update(user.getId(),new UpdateProfileRequest(null,null,oldPhone,null,null),metadata);var first=profileService.contactChallenges(user.getId()).getFirst();
        authService.verify(new VerificationRequest(null,first.id(),verificationCodes.get(oldPhone)),metadata);
        authService.forgotPassword(new ForgotPasswordRequest(oldPhone),metadata);var reset=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();String oldCode=resetCodes.get(oldPhone);
        profileService.update(user.getId(),new UpdateProfileRequest(null,null,newPhone,null,null),metadata);var replacement=profileService.contactChallenges(user.getId()).getFirst();
        authService.verify(new VerificationRequest(null,replacement.id(),verificationCodes.get(newPhone)),metadata);
        assertThatThrownBy(()->authService.resetPassword(new ResetPasswordRequest(reset.getId(),oldCode,"Replacement123!"),metadata)).isInstanceOf(IdentityException.class);
        authService.forgotPassword(new ForgotPasswordRequest(newPhone),metadata);var valid=otpRepository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(),OtpPurpose.PASSWORD_RESET).getFirst();
        authService.resetPassword(new ResetPasswordRequest(valid.getId(),resetCodes.get(newPhone),"Replacement123!"),metadata);
        String newEmail=uniqueEmail("locked-contact-target");profileService.update(user.getId(),new UpdateProfileRequest(null,newEmail,null,null,null),metadata);var change=profileService.contactChallenges(user.getId()).getFirst();
        jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",user.getId());
        assertThatThrownBy(()->authService.verify(new VerificationRequest(null,change.id(),verificationCodes.get(newEmail)),metadata)).isInstanceOf(IdentityException.class).hasMessage("Account is not active");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.LOCKED);
    }
    private com.sporthub.identity.security.AuthenticatedUser staffOwner(UUID facility){var u=activate(uniqueEmail("staff-owner"));u.getRoles().add(Role.OWNER);userRepository.saveAndFlush(u);staffFacilityOwners.put(facility,u.getId());return applicant(u);}
    private StaffBindingService.Profile staffProfile(UUID facility,com.sporthub.identity.security.AuthenticatedUser owner){return staff.createProfile(facility,new StaffBindingService.ProfileInput("Nhân viên sân",null,null,true),owner,"Bearer fixture");}
    private StaffBindingService.Binding linkStaff(UUID facility,com.sporthub.identity.security.AuthenticatedUser owner,User user,java.util.Set<String> permissions){var profile=staffProfile(facility,owner);var input=new StaffBindingService.Input(user.getEmail(),permissions);var invite=staff.invite(facility,profile.id(),input,owner,"Bearer fixture");staff.respond(invite.id(),true,applicant(user));return staff.assign(facility,input,owner,"Bearer fixture");}

    @Test void staffProfilesAreIndependentValidatedAndOwnerScoped(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);int users=jdbc.queryForObject("SELECT count(*) FROM users",Integer.class);var p=staffProfile(f,owner);assertThat(p.userId()).isNull();assertThat(p.linked()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(users);
        assertThat(staff.profiles(f,owner,"Bearer fixture")).containsExactly(p);
        assertThatThrownBy(()->staffProfile(f,applicant(activate(uniqueEmail("customer-staff-denied"))))).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThatThrownBy(()->staff.profiles(UUID.randomUUID(),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThatThrownBy(()->staff.createProfile(f,new StaffBindingService.ProfileInput("  ","invalid",null,true),owner,"Bearer fixture")).isInstanceOf(IllegalArgumentException.class);
        assertThat(staff.history(f,p.id(),owner,"Bearer fixture")).extracting(StaffBindingService.History::action).containsExactly("STAFF_PROFILE_CREATED");
    }
    @Test void staffLookupIsExactVerifiedPrivateAndCannotEnumerateOrSelectAdmins() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-lookup"));var candidate=staff.lookup(f,u.getEmail().toUpperCase(java.util.Locale.ROOT),owner,"Bearer fixture");assertThat(candidate.userId()).isEqualTo(u.getId());assertThat(candidate.maskedIdentifier()).doesNotContain(u.getEmail());
        var body=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(candidate);assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("userId","fullName","maskedIdentifier");
        assertThatThrownBy(()->staff.lookup(f,"%@example.test",owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ResourceNotFoundException.class);
        var pending=authService.register(registration(uniqueEmail("pending-staff"),null),metadata);assertThatThrownBy(()->staff.lookup(f,userRepository.findById(pending.userId()).orElseThrow().getEmail(),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ResourceNotFoundException.class);
        var admin=applicationAdmin();assertThatThrownBy(()->staff.lookup(f,admin.email(),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ResourceNotFoundException.class);
        var unverifiedPhone=uniquePhone();jdbc.update("UPDATE users SET phone=?,phone_verified=false WHERE id=?",unverifiedPhone,u.getId());assertThatThrownBy(()->staff.lookup(f,unverifiedPhone,owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ResourceNotFoundException.class);
    }
    @Test void staffCannotBeLinkedWithoutConsentAndInvitationIsRecipientOnly(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-consent"));var outsider=activate(uniqueEmail("staff-outsider"));var p=staffProfile(f,owner);var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));
        assertThatThrownBy(()->staff.assign(f,input,owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class).hasMessageContaining("accept");
        var invitation=staff.invite(f,p.id(),input,owner,"Bearer fixture");assertThat(staff.invite(f,p.id(),input,owner,"Bearer fixture").id()).isEqualTo(invitation.id());assertThat(staff.invitations(applicant(outsider))).isEmpty();assertThatThrownBy(()->staff.respond(invitation.id(),true,applicant(outsider))).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        staff.respond(invitation.id(),true,applicant(u));assertThat(staff.respond(invitation.id(),true,applicant(u)).state()).isEqualTo("ACCEPTED");
        assertThatThrownBy(()->staff.assign(f,new StaffBindingService.Input(u.getEmail(),java.util.Set.of("SCHEDULE_MANAGE")),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var before=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata);var binding=staff.assign(f,input,owner,"Bearer fixture");assertThat(binding.userId()).isEqualTo(u.getId());assertThat(staff.assign(f,input,owner,"Bearer fixture").id()).isEqualTo(binding.id());
        assertThat(userRepository.findById(u.getId()).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER,Role.STAFF);
        assertThatThrownBy(()->sessionValidation.validate(jwtService.parseAccessToken(before.response().accessToken()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=? AND action='STAFF_ASSIGNED'",Integer.class,binding.id())).isEqualTo(1);
    }
    @Test void declinedExpiredCancelledAndInactiveProfilesCannotGrantAccess(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-invalid-invitation"));var p=staffProfile(f,owner);var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));var first=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(first.id(),false,applicant(u));assertThatThrownBy(()->staff.respond(first.id(),true,applicant(u))).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var second=staff.invite(f,p.id(),input,owner,"Bearer fixture");jdbc.update("UPDATE staff_invitations SET created_at=NOW()-INTERVAL '2 days',expires_at=NOW()-INTERVAL '1 day' WHERE id=?",second.id());assertThatThrownBy(()->staff.respond(second.id(),true,applicant(u))).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var third=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(third.id(),true,applicant(u));staff.cancelInvitation(f,p.id(),owner,"Bearer fixture");assertThatThrownBy(()->staff.assign(f,input,owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var fourth=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(fourth.id(),true,applicant(u));staff.updateProfile(f,p.id(),new StaffBindingService.ProfileUpdate(p.fullName(),null,null,false,p.version()),owner,"Bearer fixture");assertThatThrownBy(()->staff.assign(f,input,owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_facility_bindings WHERE facility_id=?",Integer.class,f)).isZero();
    }
    @Test void staffPermissionsAndRevocationApplyToTheExistingSessionAtNextRequest() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-live"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ","SCHEDULE_MANAGE"));String token=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata).response().accessToken();var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token);
        http.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").isNotEmpty());
        var v=staff.permissions(f,b.id(),owner,"Bearer fixture");staff.updatePermissions(f,b.id(),new StaffBindingService.PermissionsInput(java.util.Set.of(),v.version()),owner,"Bearer fixture");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").isEmpty());
        staff.revoke(f,b.id(),owner,"Bearer fixture");staff.revoke(f,b.id(),owner,"Bearer fixture");http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings").isEmpty());
        assertThat(userRepository.findById(u.getId())).isPresent();assertThatThrownBy(()->staff.updatePermissions(f,b.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ"),v.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        assertThatThrownBy(()->staff.assign(f,new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ")),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var p=staff.profiles(f,owner,"Bearer fixture").getFirst();assertThat(staff.history(f,p.id(),owner,"Bearer fixture")).extracting(StaffBindingService.History::action).contains("STAFF_PERMISSIONS_CHANGED","STAFF_REVOKED");
    }
    @Test void staffRefundPermissionIsExplicitOwnerScopedAndRevocable() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-refund"));
        var binding=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));
        var initial=staff.permissions(f,binding.id(),owner,"Bearer fixture");
        assertThat(initial.permissions()).doesNotContain("BOOKING_REFUND");
        var allowed=staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ","BOOKING_REFUND"),initial.version()),owner,"Bearer fixture");
        assertThat(allowed.permissions()).contains("BOOKING_REFUND");
        String token=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").value(org.hamcrest.Matchers.hasItem("BOOKING_REFUND")));
        var staffActor=applicant(userRepository.findById(u.getId()).orElseThrow());
        assertThatThrownBy(()->staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_REFUND"),allowed.version()),staffActor,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThatThrownBy(()->staff.updatePermissions(UUID.randomUUID(),binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_REFUND"),allowed.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        var revoked=staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ"),allowed.version()),owner,"Bearer fixture");
        assertThat(revoked.permissions()).doesNotContain("BOOKING_REFUND");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("BOOKING_REFUND"))));
        assertThatThrownBy(()->staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_REFUND"),allowed.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    }
    @Test void staffTransferPayoutPermissionIsExplicitOwnerScopedAndRevocable() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-transfer-payout"));
        var binding=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));
        var initial=staff.permissions(f,binding.id(),owner,"Bearer fixture");
        assertThat(initial.permissions()).doesNotContain("TRANSFER_PAYOUT");
        var allowed=staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ","TRANSFER_PAYOUT"),initial.version()),owner,"Bearer fixture");
        assertThat(allowed.permissions()).contains("TRANSFER_PAYOUT");
        String token=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").value(org.hamcrest.Matchers.hasItem("TRANSFER_PAYOUT")));
        var staffActor=applicant(userRepository.findById(u.getId()).orElseThrow());
        assertThatThrownBy(()->staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("TRANSFER_PAYOUT"),allowed.version()),staffActor,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThatThrownBy(()->staff.updatePermissions(UUID.randomUUID(),binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("TRANSFER_PAYOUT"),allowed.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        var revoked=staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ"),allowed.version()),owner,"Bearer fixture");
        assertThat(revoked.permissions()).doesNotContain("TRANSFER_PAYOUT");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.facilityBindings[0].permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("TRANSFER_PAYOUT"))));
        assertThatThrownBy(()->staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("TRANSFER_PAYOUT"),allowed.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    }
    @Test void staffCannotEscalatePermissionsOrWriteAnotherFacility(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-no-escalation"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));var p=staff.permissions(f,b.id(),owner,"Bearer fixture");
        for(String permission:java.util.List.of("OWNER","ADMIN","PRICING_MANAGE","REFUND"))assertThatThrownBy(()->staff.updatePermissions(f,b.id(),new StaffBindingService.PermissionsInput(java.util.Set.of(permission),p.version()),owner,"Bearer fixture")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->staff.revoke(UUID.randomUUID(),b.id(),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThatThrownBy(()->staff.list(f,applicant(userRepository.findById(u.getId()).orElseThrow()),"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
        assertThat(staff.permissions(f,b.id(),owner,"Bearer fixture").version()).isEqualTo(p.version());
    }
    @Test void inactiveStaffProfileRevokesBindingAndStaleEditCannotRestoreIt(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-profile-status"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));var p=staff.profiles(f,owner,"Bearer fixture").getFirst();var after=staff.updateProfile(f,p.id(),new StaffBindingService.ProfileUpdate("Nhân viên mới",null,null,false,p.version()),owner,"Bearer fixture");assertThat(after.linked()).isFalse();assertThat(staff.list(f,owner,"Bearer fixture").getFirst().active()).isFalse();
        assertThatThrownBy(()->staff.updateProfile(f,p.id(),new StaffBindingService.ProfileUpdate(p.fullName(),null,null,true,p.version()),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        assertThat(userRepository.findById(u.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
    @Test void concurrentStaffLinksConsumeOneInvitationAndCreateOneBinding() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-link-race"));var p=staffProfile(f,owner);var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));var invite=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(invite.id(),true,applicant(u));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.ArrayList<java.util.concurrent.Future<UUID>>();for(int i=0;i<2;i++)calls.add(pool.submit(()->{ready.countDown();go.await();return staff.assign(f,input,owner,"Bearer fixture").id();}));assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(calls.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(calls.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_facility_bindings WHERE facility_id=?",Integer.class,f)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='STAFF_ASSIGNED' AND entity_id=(SELECT id FROM staff_facility_bindings WHERE facility_id=?)",Integer.class,f)).isEqualTo(1);
    }
    @Test void concurrentPermissionUpdatesHaveOneWinnerAndNeverMixSets() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-permission-race"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));long version=staff.permissions(f,b.id(),owner,"Bearer fixture").version();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var calls=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();for(var permissions:java.util.List.of(java.util.Set.of("SCHEDULE_MANAGE"),java.util.Set.of("COURT_MAINTENANCE_MANAGE")))calls.add(pool.submit(()->{ready.countDown();go.await();try{staff.updatePermissions(f,b.id(),new StaffBindingService.PermissionsInput(permissions,version),owner,"Bearer fixture");return true;}catch(com.sporthub.common.exception.ConflictException ex){return false;}}));assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(java.util.List.of(calls.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),calls.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        assertThat(staff.permissions(f,b.id(),owner,"Bearer fixture").permissions()).hasSize(1);assertThat(staff.permissions(f,b.id(),owner,"Bearer fixture").version()).isEqualTo(version+1);
    }
    @Test void staffPermissionUpdateAndRevokeRaceCannotReactivateAccess() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-revoke-race"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ"));long v=staff.permissions(f,b.id(),owner,"Bearer fixture").version();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var go=new java.util.concurrent.CountDownLatch(1);var update=pool.submit(()->{go.await();try{staff.updatePermissions(f,b.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("SCHEDULE_MANAGE"),v),owner,"Bearer fixture");}catch(com.sporthub.common.exception.ConflictException expected){}return null;});var revoke=pool.submit(()->{go.await();staff.revoke(f,b.id(),owner,"Bearer fixture");return null;});go.countDown();update.get(10,java.util.concurrent.TimeUnit.SECONDS);revoke.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        assertThat(staff.list(f,owner,"Bearer fixture").getFirst().active()).isFalse();
    }
    @Test void accountSuspensionDeactivationAndReactivationPreserveHistoryAndNeverRestoreSessions() throws Exception {
        var u=activate(uniqueEmail("account-lifecycle"));var admin=applicationAdmin();var login=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata);var reason=new AdminAccountDtos.Reason("Administrative account review");assertThat(adminAccounts.suspend(u.getId(),reason,admin,metadata).status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThatThrownBy(()->authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata)).isInstanceOf(IdentityException.class);assertThatThrownBy(()->authService.refresh(login.refreshToken(),metadata)).isInstanceOf(IdentityException.class);
        assertThat(adminAccounts.deactivate(u.getId(),reason,admin,metadata).status()).isEqualTo(AccountStatus.DEACTIVATED);assertThat(adminAccounts.reactivate(u.getId(),reason,admin,metadata).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThatThrownBy(()->sessionValidation.validate(jwtService.parseAccessToken(login.response().accessToken()))).isInstanceOf(IllegalArgumentException.class);assertThat(authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata).response().user().status()).isEqualTo(AccountStatus.ACTIVE);
        var detail=adminAccounts.detail(u.getId(),admin);assertThat(detail.audit()).extracting(AdminAccountDtos.Audit::action).contains("ADMIN_ACCOUNT_SUSPENDED","ADMIN_ACCOUNT_DEACTIVATED","ADMIN_ACCOUNT_ACTIVE");assertThat(detail.account().roles()).containsExactly(Role.CUSTOMER);assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE user_id=?",Integer.class,u.getId())).isEqualTo(3);
    }
    @Test void accountStatusCommandsRejectSelfOtherAdminsCustomersUnverifiedAndInvalidTransitions(){
        var admin=applicationAdmin();var other=applicationAdmin();var customer=activate(uniqueEmail("account-guard"));var reason=new AdminAccountDtos.Reason("Valid administrative reason");
        for(var target:java.util.List.of(admin.userId(),other.userId())){assertThatThrownBy(()->adminAccounts.suspend(target,reason,admin,metadata)).isInstanceOf(IdentityException.class);assertThatThrownBy(()->adminAccounts.deactivate(target,reason,admin,metadata)).isInstanceOf(IdentityException.class);assertThatThrownBy(()->adminAccounts.reactivate(target,reason,admin,metadata)).isInstanceOf(IdentityException.class);}
        assertThatThrownBy(()->adminAccounts.deactivate(customer.getId(),reason,applicant(customer),metadata)).isInstanceOf(IdentityException.class);assertThatThrownBy(()->adminAccounts.reactivate(customer.getId(),reason,admin,metadata)).isInstanceOf(IdentityException.class);
        var pending=authService.register(registration(uniqueEmail("account-unverified"),null),metadata);assertThatThrownBy(()->adminAccounts.deactivate(pending.userId(),reason,admin,metadata)).isInstanceOf(IdentityException.class);
        adminAccounts.lock(customer.getId(),new AdminAccountDtos.LockInput("Review","PERMANENT"),admin,metadata);assertThatThrownBy(()->adminAccounts.reactivate(customer.getId(),reason,admin,metadata)).isInstanceOf(IdentityException.class);assertThat(adminAccounts.unlock(customer.getId(),reason,admin,metadata).status()).isEqualTo(AccountStatus.ACTIVE);
    }
    @Test void concurrentAccountStatusAndRoleUpdatesDoNotLoseEitherChange() throws Exception {
        var admin=applicationAdmin();var u=activate(uniqueEmail("account-status-role-race"));try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var go=new java.util.concurrent.CountDownLatch(1);var status=pool.submit(()->{go.await();return adminAccounts.deactivate(u.getId(),new AdminAccountDtos.Reason("Concurrent security review"),admin,metadata);});var role=pool.submit(()->{go.await();return adminAccounts.roles(u.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER,Role.STAFF),"Concurrent role update"),admin,metadata);});go.countDown();status.get(10,java.util.concurrent.TimeUnit.SECONDS);role.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        var account=userRepository.findById(u.getId()).orElseThrow();assertThat(account.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);assertThat(account.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER,Role.STAFF);
    }
    @Test void ownerActivationPausedByDeactivationResumesOnlyAfterAccountReactivation(){
        var u=activate(uniqueEmail("owner-deactivation-saga"));var actor=applicant(u);var admin=applicationAdmin();var app=applications.create(applicationInput(),actor);applications.submit(app.id(),actor,null);
        org.mockito.Mockito.doAnswer(a->{adminAccounts.deactivate(u.getId(),new AdminAccountDtos.Reason("Applicant temporarily unavailable"),admin,metadata);return null;}).when(applicationDependencies).wallet(any(),any(),any());
        assertThat(applications.decide(app.id(),new OwnerApplicationDtos.Decision("APPROVE","Approved after review",new java.math.BigDecimal("10")),admin).state()).isEqualTo("APPROVING");assertThat(userRepository.findById(u.getId()).orElseThrow().getRoles()).doesNotContain(Role.OWNER);assertThat(applications.committed(java.util.List.of(app.id()))).isEmpty();
        adminAccounts.reactivate(u.getId(),new AdminAccountDtos.Reason("Applicant verified after review"),admin,metadata);org.mockito.Mockito.doReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()).when(applicationDependencies).wallet(any(),any(),any());assertThat(applications.retry(app.id(),admin).state()).isEqualTo("APPROVED");assertThat(applications.committed(java.util.List.of(app.id()))).containsExactly(app.id());assertThat(jdbc.queryForObject("SELECT count(*) FROM owner_profiles WHERE user_id=? AND approval_status='APPROVED'",Integer.class,u.getId())).isEqualTo(1);
    }

    @Test void staffHttpFlowRequiresRealSessionConsentAndReturnsOnlySafeLookupFields() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-http"));String base="/api/v1/staff-profiles/facilities/"+f;String ownerToken=authService.login(new LoginRequest(owner.email(),"Password123!"),metadata).response().accessToken();String userToken=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata).response().accessToken();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base).header("Authorization","Bearer "+userToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).header("Authorization","Bearer "+ownerToken).contentType("application/json").content("{\"fullName\":\"Staff HTTP\",\"active\":true}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();UUID profile=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/staff-bindings/facilities/"+f+"/account-lookup").header("Authorization","Bearer "+ownerToken).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("identifier",u.getEmail())))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.email").doesNotExist()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.roles").doesNotExist());
        var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));var invitation=staff.invite(f,profile,input,owner,"Bearer "+ownerToken);http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/users/me/staff-invitations/"+invitation.id()+"/accept").header("Authorization","Bearer "+userToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.state").value("ACCEPTED"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/staff-bindings/facilities/"+f).header("Authorization","Bearer "+ownerToken).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me/access").header("Authorization","Bearer "+userToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test void staffLinkRollsBackRolesConsentBindingAndSessionRevocationWhenAuditFails(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-rollback"));var p=staffProfile(f,owner);var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));var invitation=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(invitation.id(),true,applicant(u));var session=authService.login(new LoginRequest(u.getEmail(),"Password123!"),metadata);String name="phase08_audit_failure_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='STAFF_ASSIGNED' AND NEW.new_value->>'facilityId'='"+f+"' THEN RAISE EXCEPTION 'Synthetic Phase 08 audit failure'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        assertThatThrownBy(()->staff.assign(f,input,owner,"Bearer fixture")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_facility_bindings WHERE facility_id=?",Integer.class,f)).isZero();assertThat(jdbc.queryForObject("SELECT state FROM staff_invitations WHERE id=?",String.class,invitation.id())).isEqualTo("ACCEPTED");assertThat(userRepository.findById(u.getId()).orElseThrow().getRoles()).containsExactly(Role.CUSTOMER);assertThat(sessionValidation.validate(jwtService.parseAccessToken(session.response().accessToken())).userId()).isEqualTo(u.getId());
        jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER "+name);assertThat(staff.assign(f,input,owner,"Bearer fixture").active()).isTrue();
    }

    @Test void removingAndRegrantingStaffRoleNeverResurrectsOldFacilityPermissions(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var u=activate(uniqueEmail("staff-role-regrant"));var b=linkStaff(f,owner,u,java.util.Set.of("BOOKING_READ","SCHEDULE_MANAGE"));var admin=applicationAdmin();adminAccounts.roles(u.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER),"Staff employment access removed"),admin,metadata);assertThat(staff.list(f,owner,"Bearer fixture").getFirst().active()).isFalse();
        adminAccounts.roles(u.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER,Role.STAFF),"Staff role restored without employment"),admin,metadata);assertThat(staff.list(f,owner,"Bearer fixture").getFirst().active()).isFalse();assertThatThrownBy(()->staff.assign(f,new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ")),owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
        var p=staff.profiles(f,owner,"Bearer fixture").getFirst();assertThat(staff.history(f,p.id(),owner,"Bearer fixture")).extracting(StaffBindingService.History::action).contains("ADMIN_STAFF_ACCESS_REVOKED");
        var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("BOOKING_READ"));var invitation=staff.invite(f,p.id(),input,owner,"Bearer fixture");staff.respond(invitation.id(),true,applicant(userRepository.findById(u.getId()).orElseThrow()));assertThat(staff.assign(f,input,owner,"Bearer fixture").id()).isEqualTo(b.id());
    }
    @Test void adminStaffRoleRemovalAlsoCancelsAcceptedInvitations(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();var owner=staffOwner(first);staffFacilityOwners.put(second,owner.userId());var u=activate(uniqueEmail("staff-role-invitation"));linkStaff(first,owner,u,java.util.Set.of("BOOKING_READ"));var p=staffProfile(second,owner);var input=new StaffBindingService.Input(u.getEmail(),java.util.Set.of("SCHEDULE_MANAGE"));var invitation=staff.invite(second,p.id(),input,owner,"Bearer fixture");staff.respond(invitation.id(),true,applicant(userRepository.findById(u.getId()).orElseThrow()));
        adminAccounts.roles(u.getId(),new AdminAccountDtos.RolesInput(java.util.Set.of(Role.CUSTOMER),"Revoke all Staff facility access"),applicationAdmin(),metadata);assertThat(jdbc.queryForObject("SELECT state FROM staff_invitations WHERE id=?",String.class,invitation.id())).isEqualTo("CANCELLED");assertThatThrownBy(()->staff.assign(second,input,owner,"Bearer fixture")).isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    }

    @Test void counterCustomerLookupRequiresLiveCounterPermissionAndVerifiedPhone() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var staffUser=activate(uniqueEmail("counter-lookup-staff"));var binding=linkStaff(f,owner,staffUser,java.util.Set.of("BOOKING_CREATE_COUNTER"));var customer=activate(uniqueEmail("counter-linked-customer"));String phone=uniquePhone();jdbc.update("UPDATE users SET phone=?,phone_verified=true WHERE id=?",phone,customer.getId());String token=authService.login(new LoginRequest(staffUser.getEmail(),"Password123!"),metadata).response().accessToken();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String path="/api/v1/users/counter-customer-lookup";
        var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone",phone)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.customerId").value(customer.getId().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.phone").doesNotExist()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.fullName").doesNotExist()).andReturn();assertThat(result.getResponse().getContentAsString()).doesNotContain(phone);
        var permission=staff.permissions(f,binding.id(),owner,"Bearer fixture");staff.updatePermissions(f,binding.id(),new StaffBindingService.PermissionsInput(java.util.Set.of("BOOKING_READ"),permission.version()),owner,"Bearer fixture");http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone",phone)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test void counterLookupRejectsForeignStaffCustomerWildcardAndNeverMatchesInactiveUnverifiedAdmin() throws Exception {
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var staffUser=activate(uniqueEmail("counter-lookup-scope"));linkStaff(f,owner,staffUser,java.util.Set.of("BOOKING_CREATE_COUNTER"));String token=authService.login(new LoginRequest(staffUser.getEmail(),"Password123!"),metadata).response().accessToken();var customer=activate(uniqueEmail("counter-lookup-ineligible"));String phone=uniquePhone();jdbc.update("UPDATE users SET phone=?,phone_verified=false WHERE id=?",phone,customer.getId());String path="/api/v1/users/counter-customer-lookup";var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone",phone)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.customerId").isEmpty());
        jdbc.update("UPDATE users SET phone_verified=true,status='LOCKED' WHERE id=?",customer.getId());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone",phone)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.customerId").isEmpty());
        for(String body:java.util.List.of(mapper.writeValueAsString(java.util.Map.of("facilityId",java.util.UUID.randomUUID(),"phone",phone)),mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone","%"))))http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
        var admin=applicationAdmin();String adminPhone=uniquePhone();jdbc.update("UPDATE users SET phone=?,phone_verified=true WHERE id=?",adminPhone,admin.userId());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("facilityId",f,"phone",adminPhone)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.customerId").isEmpty());
    }
    @Test void counterPaymentPermissionCanBeAssignedAndCatalogUpdated(){
        UUID f=UUID.randomUUID();var owner=staffOwner(f);var staffUser=activate(uniqueEmail("counter-payment-permission"));var binding=linkStaff(f,owner,staffUser,java.util.Set.of("BOOKING_PAYMENT_COUNTER"));assertThat(staff.permissions(f,binding.id(),owner,"Bearer fixture").permissions()).containsExactly("BOOKING_PAYMENT_COUNTER");assertThat(staff.catalog(f,owner,"Bearer fixture")).contains("BOOKING_PAYMENT_COUNTER").doesNotContain("BOOKING_CANCEL_PENDING","BOOKING_COMPLETE","REFUND");
    }


 @Test void facilityStatusNoticeDeduplicatesByDecisionAndRejectsUntrustedProducer(){
  var user=activate(uniqueEmail("facility-status"));UUID decision=UUID.randomUUID();var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var event=mapper.valueToTree(com.sporthub.common.event.DomainEvent.create("facility.status.changed",1,"facility-service",UUID.randomUUID(),Map.of("decisionId",decision,"ownerId",user.getId(),"facilityName","Synthetic Facility","state","SUSPENDED","reason","Verified safety violation decision")));
  facilityNotices.apply(event);facilityNotices.apply(event);((com.fasterxml.jackson.databind.node.ObjectNode)event).put("eventId",UUID.randomUUID().toString());facilityNotices.apply(event);assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_notifications WHERE source_key=?",Integer.class,"facility-status:"+decision)).isEqualTo(1);
  ((com.fasterxml.jackson.databind.node.ObjectNode)event).put("producer","untrusted");assertThatThrownBy(()->facilityNotices.apply(event)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
 }

}
