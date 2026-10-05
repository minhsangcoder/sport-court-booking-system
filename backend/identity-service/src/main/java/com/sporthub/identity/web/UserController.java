package com.sporthub.identity.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.identity.service.ProfileService;
import com.sporthub.identity.service.RequestMetadata;
import com.sporthub.identity.web.dto.UpdateProfileRequest;
import com.sporthub.identity.web.dto.UserProfileView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final ProfileService profileService;

    @GetMapping("/me/contact-challenges")
    public ApiResponse<java.util.List<ProfileService.ContactChallenge>> contactChallenges(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(profileService.contactChallenges(principal.userId()));
    }

    @PostMapping("/me/contact-challenges/{channel}/resend")
    public ApiResponse<ProfileService.ContactChallenge> resendContact(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String channel,
            HttpServletRequest request) {
        return ApiResponse.success(profileService.resendContact(principal.userId(), channel, RequestMetadata.from(request)));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileView> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.success(profileService.get(principal.userId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("Profile updated",
                profileService.update(principal.userId(), body, RequestMetadata.from(request)));
    }
}
