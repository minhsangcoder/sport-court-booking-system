package com.sporthub.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @Email @Size(max = 254) String email,
        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        Boolean applyAsOwner,
        com.fasterxml.jackson.databind.JsonNode ownerApplication) {
    public RegisterRequest(String fullName,String email,String phone,String password){this(fullName,email,phone,password,false,null);}
    public boolean ownerOption(){return Boolean.TRUE.equals(applyAsOwner);}

    @jakarta.validation.constraints.AssertTrue(message = "Email or phone is required")
    public boolean isContactPresent() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }
}
