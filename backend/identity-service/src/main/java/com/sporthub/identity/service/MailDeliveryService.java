package com.sporthub.identity.service;

import com.sporthub.identity.config.IdentityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MailDeliveryService {
    private final JavaMailSender mailSender;
    private final IdentityProperties properties;
    @org.springframework.beans.factory.annotation.Value("${sporthub.identity.demo-sms-enabled:false}")
    private boolean demoSmsEnabled;

    public void sendVerification(String recipient, String code, String token, Instant expiresAt) {
        send(recipient, "Verify your SportHub account", """
                Your SportHub verification code is: %s

                Or open this link:
                %s/verify?token=%s

                This challenge expires at %s.
                """.formatted(code, properties.frontendBaseUrl(), token, expiresAt));
    }

    public void sendPasswordReset(String recipient, UUID challengeId, String code, Instant expiresAt) {
        send(recipient, "Reset your SportHub password", """
                Your SportHub password reset code is: %s

                Open this link to enter the code:
                %s/reset-password?challengeId=%s

                This code expires at %s and can be used only once.
                """.formatted(code, properties.frontendBaseUrl(), challengeId, expiresAt));
    }

    public void sendNotification(String recipient,String subject,String body){send(recipient,subject,body);}

    private void send(String recipient, String subject, String body) {
        if (recipient.startsWith("+")) {
            if (!demoSmsEnabled) throw new com.sporthub.identity.exception.IdentityException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY-SMS-UNAVAILABLE", "SMS delivery is not configured");
            subject = "[LOCAL DEMO SMS to " + recipient + "] " + subject;
            recipient = recipient.substring(1) + "@sms.sporthub.local";
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
