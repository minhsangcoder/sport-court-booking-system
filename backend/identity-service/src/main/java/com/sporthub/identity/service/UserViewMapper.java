package com.sporthub.identity.service;

import com.sporthub.identity.domain.User;
import com.sporthub.identity.domain.UserProfile;
import com.sporthub.identity.web.dto.UserProfileView;
import org.springframework.stereotype.Component;

@Component
public class UserViewMapper {
    public UserProfileView toView(User user) {
        UserProfile profile = user.getProfile();
        return new UserProfileView(
                user.getId(),
                profile == null ? null : profile.getFullName(),
                user.getEmail(),
                user.getPhone(),
                profile == null ? null : profile.getAvatarUrl(),
                profile == null ? null : profile.getDateOfBirth(),
                user.getRoles(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getCreatedAt());
    }
}
