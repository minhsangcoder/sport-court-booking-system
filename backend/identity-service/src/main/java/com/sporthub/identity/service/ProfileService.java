package com.sporthub.identity.service;

import com.sporthub.identity.domain.OtpPurpose;
import com.sporthub.identity.domain.User;
import com.sporthub.identity.domain.UserProfile;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.UserRepository;
import com.sporthub.identity.web.dto.UpdateProfileRequest;
import com.sporthub.identity.web.dto.UserProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final UserRepository userRepository;
    private final UserViewMapper mapper;
    private final ChallengeService challengeService;
    private final AuditService auditService;

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
            if (!email.equalsIgnoreCase(user.getEmail())) {
                if (userRepository.existsByEmailIgnoreCase(email) || userRepository.existsByPendingEmailIgnoreCase(email)) {
                    throw new IdentityException(HttpStatus.CONFLICT, "IDENTITY-EMAIL-EXISTS", "Email is already registered");
                }
                user.setPendingEmail(email);
                challengeService.issue(user, OtpPurpose.EMAIL_CHANGE, email);
            }
        }

        if (request.phone() != null) {
            String phone = blankToNull(request.phone());
            if (phone != null && !phone.equals(user.getPhone())) {
                if (userRepository.existsByPhone(phone) || userRepository.existsByPendingPhone(phone)) {
                    throw new IdentityException(HttpStatus.CONFLICT, "IDENTITY-PHONE-EXISTS", "Phone is already registered");
                }
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
