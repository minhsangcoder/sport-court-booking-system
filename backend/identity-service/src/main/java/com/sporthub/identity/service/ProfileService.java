package com.sporthub.identity.service;

import com.sporthub.identity.domain.OtpPurpose;
import com.sporthub.identity.domain.User;
import com.sporthub.identity.domain.UserProfile;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.UserRepository;
import com.sporthub.identity.repository.OtpCodeRepository;
import com.sporthub.identity.domain.OtpCode;
import com.sporthub.identity.web.dto.UpdateProfileRequest;
import com.sporthub.identity.web.dto.UserProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final UserRepository userRepository;
    private final UserViewMapper mapper;
    private final ChallengeService challengeService;
    private final AuditService auditService;
    private final OtpCodeRepository otpRepository;

    public record ContactChallenge(UUID id, String channel, String recipient, Instant expiresAt,
                                   Instant resendAfter, boolean usable) {}

    @Transactional(readOnly = true)
    public List<ContactChallenge> contactChallenges(UUID userId) {
        User user = load(userId);
        return java.util.stream.Stream.of(OtpPurpose.EMAIL_CHANGE, OtpPurpose.PHONE_CHANGE)
                .flatMap(purpose -> otpRepository.findByUserIdAndPurposeAndVerifiedFalse(userId, purpose).stream()
                        .filter(challenge -> Objects.equals(challenge.getRecipient(),
                                purpose == OtpPurpose.EMAIL_CHANGE ? user.getPendingEmail() : user.getPendingPhone()))
                        .map(this::contactView)).toList();
    }

    @Transactional
    public ContactChallenge resendContact(UUID userId, String channel, RequestMetadata metadata) {
        OtpPurpose purpose = switch (channel) {
            case "EMAIL" -> OtpPurpose.EMAIL_CHANGE;
            case "PHONE" -> OtpPurpose.PHONE_CHANGE;
            default -> throw new IdentityException(HttpStatus.BAD_REQUEST, "IDENTITY-VALIDATION", "Choose EMAIL or PHONE");
        };
        User user = load(userId);
        String recipient = purpose == OtpPurpose.EMAIL_CHANGE ? user.getPendingEmail() : user.getPendingPhone();
        if (recipient == null) throw new IdentityException(HttpStatus.CONFLICT, "IDENTITY-CHANGE-NOT-PENDING", "No contact change is pending");
        checkContactCooldown(userId, purpose);
        ChallengeIssued issued = challengeService.issue(user, purpose, recipient);
        auditService.record(userId, "CONTACT_CHALLENGE_RESENT", "USER", userId, metadata);
        return contactView(otpRepository.findById(issued.id()).orElseThrow());
    }

    private ContactChallenge contactView(OtpCode challenge) {
        return new ContactChallenge(challenge.getId(), challenge.getPurpose() == OtpPurpose.EMAIL_CHANGE ? "EMAIL" : "PHONE",
                challenge.getRecipient(), challenge.getExpiresAt(), challenge.getCreatedAt().plusSeconds(60), challenge.isUsableAt(Instant.now()));
    }

    private void checkContactCooldown(UUID userId, OtpPurpose purpose) {
        if (otpRepository.findByUserIdAndPurposeAndVerifiedFalse(userId, purpose).stream()
                .anyMatch(challenge -> challenge.getCreatedAt().plusSeconds(60).isAfter(Instant.now()))) {
            throw new IdentityException(HttpStatus.TOO_MANY_REQUESTS, "IDENTITY-CHALLENGE-COOLDOWN", "Please wait 60 seconds before requesting another code");
        }
    }

    @Transactional(readOnly = true)
    public UserProfileView get(UUID userId) {
        return mapper.toView(load(userId));
    }

    @Transactional
    public UserProfileView update(UUID userId, UpdateProfileRequest request, RequestMetadata metadata) {
        if (request.fullName() == null && request.email() == null && request.phone() == null
                && request.avatarUrl() == null && request.dateOfBirth() == null) {
            throw new IdentityException(HttpStatus.BAD_REQUEST, "IDENTITY-VALIDATION", "At least one profile field is required");
        }
        User user = load(userId);
        UserProfile profile = user.getProfile();
        if (request.fullName() != null) profile.setFullName(request.fullName().trim());
        if (request.avatarUrl() != null) profile.setAvatarUrl(blankToNull(request.avatarUrl()));
        if (request.dateOfBirth() != null) profile.setDateOfBirth(request.dateOfBirth());

        if (request.email() != null) {
            String email = request.email().trim().toLowerCase(Locale.ROOT);
            if (!email.equalsIgnoreCase(user.getEmail()) && !email.equalsIgnoreCase(user.getPendingEmail())) {
                if (userRepository.existsByEmailIgnoreCase(email) || userRepository.existsByPendingEmailIgnoreCase(email)) {
                    throw new IdentityException(HttpStatus.CONFLICT, "IDENTITY-EMAIL-EXISTS", "Email is already registered");
                }
                checkContactCooldown(userId, OtpPurpose.EMAIL_CHANGE);
                user.setPendingEmail(email);
                challengeService.issue(user, OtpPurpose.EMAIL_CHANGE, email);
            }
        }

        if (request.phone() != null) {
            String phone = blankToNull(request.phone());
            if (phone != null && !phone.equals(user.getPhone()) && !phone.equals(user.getPendingPhone())) {
                if (userRepository.existsByPhone(phone) || userRepository.existsByPendingPhone(phone)) {
                    throw new IdentityException(HttpStatus.CONFLICT, "IDENTITY-PHONE-EXISTS", "Phone is already registered");
                }
                checkContactCooldown(userId, OtpPurpose.PHONE_CHANGE);
                user.setPendingPhone(phone);
                challengeService.issue(user, OtpPurpose.PHONE_CHANGE, phone);
            }
        }

        auditService.record(user.getId(), "PROFILE_UPDATED", "USER", user.getId(), metadata);
        return mapper.toView(user);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() ->
                new IdentityException(HttpStatus.NOT_FOUND, "IDENTITY-USER-NOT-FOUND", "User was not found"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
