package com.memecreator.contact;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.Throttle;
import com.memecreator.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * The contact form: emails each message to the admins (meme.security.admin-emails) with Reply-To set to the sender,
 * so answering is just "Reply". Messages are not stored by the app. Without email set up they are logged instead.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int PER_ADDRESS = 5;   // per sender email and per IP address, per hour

    private final ObjectProvider<JavaMailSender> sender;
    private final AccountProperties accountProps;
    private final SecurityProperties security;
    private final Throttle throttle;

    public ContactService(ObjectProvider<JavaMailSender> sender, AccountProperties accountProps,
                          SecurityProperties security, Throttle throttle) {
        this.sender = sender;
        this.accountProps = accountProps;
        this.security = security;
        this.throttle = throttle;
    }

    /** The message couldn't be delivered; the visitor should try again or write directly. */
    public static final class NotSentException extends RuntimeException {
        public NotSentException() {
            super("Your message couldn't be sent. Try again in a few minutes.");
        }
    }

    /**
     * @param fromIp    for the hourly limit
     * @param accountId the sender's account when signed in, or null
     */
    public void send(String email, String subject, String message, String fromIp, Long accountId) {
        String replyTo = email.strip();
        throttle.check("contact:ip:" + fromIp, PER_ADDRESS, WINDOW);
        throttle.check("contact:email:" + replyTo.toLowerCase(), PER_ADDRESS, WINDOW);
        throttle.record("contact:ip:" + fromIp);
        throttle.record("contact:email:" + replyTo.toLowerCase());

        String cleanSubject = oneLine(subject);
        String text = """
                %s

                ---
                From: %s
                Account: %s
                Reply to this email to answer.
                """.formatted(message.strip(), replyTo, accountId == null ? "not signed in" : "#" + accountId);
        List<String> to = security.adminEmails().stream().filter(e -> e != null && !e.isBlank()).map(String::strip)
                .toList();
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null || to.isEmpty()) {
            log.warn("Contact message (email not set up or no meme.security.admin-emails):\nFrom: {}\nSubject: {}\n\n{}",
                    replyTo, cleanSubject, text);
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(accountProps.mailFrom());
        m.setTo(to.toArray(String[]::new));
        m.setReplyTo(replyTo);
        m.setSubject("[Contact] " + cleanSubject);
        m.setText(text);
        try {
            mail.send(m);
            log.info("Contact message from {} sent to the admins", replyTo);
        } catch (MailException e) {
            log.error("Could not send contact message from {}: {}", replyTo, e.getMessage());
            throw new NotSentException();
        }
    }

    /** No line breaks in a subject (header injection), no control characters, at most 150 characters. */
    static String oneLine(String s) {
        String c = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").strip();
        return c.length() > 150 ? c.substring(0, 150) : c;
    }
}
