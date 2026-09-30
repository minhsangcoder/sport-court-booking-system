package com.sporthub.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Centralized error codes for all SportHub microservices.
 * Convention: {DOMAIN}-{NNN}
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── Common ─────────────────────────────────────────────────────────
    RESOURCE_NOT_FOUND("COMMON-001", "Resource not found"),
    CONFLICT("COMMON-002", "Resource conflict"),
    UNAUTHORIZED("COMMON-003", "Unauthorized access"),
    FORBIDDEN("COMMON-004", "Access forbidden"),
    VALIDATION_ERROR("COMMON-005", "Validation failed"),
    INTERNAL_ERROR("COMMON-006", "Internal server error"),
    BUSINESS_RULE_VIOLATION("COMMON-007", "Business rule violation"),
    OPTIMISTIC_LOCK_CONFLICT("COMMON-008", "Resource was modified by another user"),

    // ── Identity & Auth ────────────────────────────────────────────────
    INVALID_CREDENTIALS("AUTH-001", "Invalid credentials"),
    TOKEN_EXPIRED("AUTH-002", "Token has expired"),
    TOKEN_INVALID("AUTH-003", "Invalid token"),
    ACCOUNT_LOCKED("AUTH-004", "Account is locked"),
    ACCOUNT_NOT_VERIFIED("AUTH-005", "Account is not verified"),
    OTP_EXPIRED("AUTH-006", "OTP has expired"),
    OTP_INVALID("AUTH-007", "Invalid OTP"),
    EMAIL_ALREADY_EXISTS("AUTH-008", "Email already registered"),
    PHONE_ALREADY_EXISTS("AUTH-009", "Phone number already registered"),
    REFRESH_TOKEN_INVALID("AUTH-010", "Invalid refresh token"),

    // ── Facility ───────────────────────────────────────────────────────
    FACILITY_NOT_APPROVED("FACILITY-001", "Facility not yet approved"),
    FACILITY_SUSPENDED("FACILITY-002", "Facility is suspended"),
    COURT_NOT_AVAILABLE("FACILITY-003", "Court is not available"),
    FACILITY_DUPLICATE_NAME("FACILITY-004", "Facility name already exists for this owner"),

    // ── Schedule & Pricing ─────────────────────────────────────────────
    SCHEDULE_OVERLAP("SCHEDULE-001", "Schedule overlaps with existing entry"),
    PRICING_RULE_CONFLICT("SCHEDULE-002", "Pricing rule conflicts with existing rule"),
    EXCEPTION_CALENDAR_OVERLAP("SCHEDULE-003", "Exception calendar entry overlaps"),

    // ── Booking ────────────────────────────────────────────────────────
    SLOT_ALREADY_HELD("BOOKING-001", "Slot is already held by another user"),
    SLOT_HOLD_EXPIRED("BOOKING-002", "Slot hold has expired (10-minute TTL)"),
    BOOKING_CANNOT_CANCEL("BOOKING-003", "Booking cannot be cancelled in current state"),
    BOOKING_ALREADY_CHECKED_IN("BOOKING-004", "Booking has already been checked in"),
    INVALID_STATE_TRANSITION("BOOKING-005", "Invalid booking state transition"),
    GROUP_PAYMENT_INCOMPLETE("BOOKING-006", "Not all group members have completed payment"),
    GROUP_MEMBER_LIMIT("BOOKING-007", "Group member limit exceeded"),

    // ── Payment ────────────────────────────────────────────────────────
    PAYMENT_FAILED("PAYMENT-001", "Payment processing failed"),
    REFUND_FAILED("PAYMENT-002", "Refund processing failed"),
    ESCROW_INSUFFICIENT("PAYMENT-003", "Insufficient escrow balance"),
    PAYMENT_ALREADY_COMPLETED("PAYMENT-004", "Payment has already been completed"),
    RECONCILIATION_MISMATCH("PAYMENT-005", "Reconciliation mismatch detected"),

    // ── Transfer Marketplace ───────────────────────────────────────────
    TRANSFER_PRICE_EXCEEDS_ORIGINAL("TRANSFER-001", "Transfer price cannot exceed original booking price"),
    LISTING_ALREADY_LOCKED("TRANSFER-002", "Listing is already locked by another buyer"),
    LISTING_EXPIRED("TRANSFER-003", "Transfer listing has expired"),
    LISTING_ALREADY_SOLD("TRANSFER-004", "Transfer listing has already been sold");

    private final String code;
    private final String defaultMessage;
}
