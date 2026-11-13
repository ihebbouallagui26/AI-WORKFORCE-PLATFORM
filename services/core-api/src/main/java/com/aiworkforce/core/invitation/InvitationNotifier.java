package com.aiworkforce.core.invitation;

import com.aiworkforce.core.common.exception.ServiceUnavailableException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends invitation e-mails. The token appears only in the message body: it is never logged and
 * never returned by the API. If no SMTP server is configured or sending fails, the invitation
 * is not created (the caller's transaction rolls back) and the API answers 503.
 */
@Component
class InvitationNotifier {

    private static final Logger log = LoggerFactory.getLogger(InvitationNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final InvitationProperties properties;

    InvitationNotifier(ObjectProvider<JavaMailSender> mailSender, InvitationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    void sendInvitation(String email, String organizationName, String roleName, String inviterName, String token,
            Instant expiresAt) {
        organizationName = singleLine(organizationName);
        roleName = singleLine(roleName);
        inviterName = singleLine(inviterName);
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new ServiceUnavailableException("E-mail delivery is not configured.", null);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Invitation to join " + organizationName + " on AI Workforce");
        message.setText("""
                Hello,

                %s invited you to join the organization "%s" on AI Workforce with the role "%s".

                To accept, sign in to the AI Workforce desktop application with this e-mail address,
                open "Accept an invitation" and paste the following code:

                    %s

                This code is personal, can be used only once and expires on %s.
                If you were not expecting this invitation, you can ignore this message.
                """.formatted(inviterName, organizationName, roleName, token, DATE.format(expiresAt)));
        try {
            sender.send(message);
        } catch (MailException e) {
            // Never log the message itself (it contains the token).
            log.warn("Invitation e-mail could not be sent: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("The invitation e-mail could not be sent.", e);
        }
    }

    /** User-controlled values must not inject headers or fake lines into the message. */
    static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
