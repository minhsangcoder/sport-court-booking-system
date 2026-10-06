package com.sporthub.common.dto;

import java.util.Locale;

/** Contact data only; follows Identity's existing trim/lowercase email convention. */
public final class ContactEmail {
    private ContactEmail() {}

    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
