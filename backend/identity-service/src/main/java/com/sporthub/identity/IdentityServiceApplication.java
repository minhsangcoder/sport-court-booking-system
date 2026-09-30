package com.sporthub.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SportHub Identity & Access Service — Microservice entry point.
 *
 * <p>Responsibilities (UC-1.x):</p>
 * <ul>
 *   <li>User registration, login, JWT issuance (Access + Refresh tokens)</li>
 *   <li>OAuth2 / OTP verification</li>
 *   <li>RBAC: Customer, Owner, Staff, Admin roles</li>
 *   <li>Staff-Facility binding (facility-scoped permissions in JWT claims)</li>
 *   <li>Owner registration with business documents</li>
 *   <li>Account management (lock, unlock, profile update)</li>
 * </ul>
 *
 * <p>Scans both {@code com.sporthub.identity} (service code) and
 * {@code com.sporthub.common} (shared infrastructure) packages.</p>
 */
@SpringBootApplication(scanBasePackages = {"com.sporthub.identity", "com.sporthub.common"})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
