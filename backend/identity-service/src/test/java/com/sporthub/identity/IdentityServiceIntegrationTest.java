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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean MailDeliveryService mailDelivery;
    @MockBean OwnerApplicationDependencies applicationDependencies;
    @Autowired OwnerApplicationService applications;
    @Autowired OwnerApplicationCipher applicationCipher;

    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, String> resetCodes = new ConcurrentHashMap<>();
    private final RequestMetadata metadata = new RequestMetadata("127.0.0.1", "identity-integration-test");

    @BeforeEach
    void captureOutboundCodes() {
        reset(mailDelivery);
        reset(applicationDependencies);
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

    private com.sporthub.identity.security.AuthenticatedUser applicant(User user){return new com.sporthub.identity.security.AuthenticatedUser(user.getId(),user.getEmail(),java.util.Set.copyOf(user.getRoles()),UUID.randomUUID());}
    private com.sporthub.identity.security.AuthenticatedUser applicationAdmin(){var u=activate(uniqueEmail("application-admin"));u.getRoles().add(Role.ADMIN);userRepository.saveAndFlush(u);return applicant(u);}
    private OwnerApplicationDtos.Create applicationInput(){return new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility("First facility","+84901234567","Demo address","Ha Noi","Cau Giay","Demo ward",null,"Asia/Ho_Chi_Minh",new java.math.BigDecimal("21.03"),new java.math.BigDecimal("105.78"),java.util.Set.of()));}
    private OwnerApplicationDtos.Legal applicationLegal(){return new OwnerApplicationDtos.Legal("Demo representative","TEST-IDENTITY-PRIVATE","Demo business",null,null,"Demo bank","Demo representative","TEST-BANK-PRIVATE");}
    private OwnerApplicationDtos.Decision approval(){return new OwnerApplicationDtos.Decision("APPROVE","Application reviewed and accepted",new java.math.BigDecimal("5.00"));}


    private OwnerApplicationDtos.Create contactApplicationInput(String email){
        var f=applicationInput().facility();return new OwnerApplicationDtos.Create(applicationLegal(),new OwnerApplicationDtos.Facility(f.name(),f.phone(),f.addressLine(),f.province(),f.district(),f.ward(),f.description(),f.timezone(),f.latitude(),f.longitude(),f.amenities(),email));
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

}
