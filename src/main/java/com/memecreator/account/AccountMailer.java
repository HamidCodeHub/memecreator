package com.memecreator.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the account emails. Without SMTP settings (spring.mail.host unset) it writes them to the log instead,
 * so sign-up can be tried locally: copy the link from the console.
 */
@Component
public class AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final AccountProperties props;

    public AccountMailer(ObjectProvider<JavaMailSender> sender, AccountProperties props) {
        this.sender = sender;
        this.props = props;
    }

    public void confirmEmail(String to, String link) {
        send(to, "Confirm your email for Memes for Laughs", """
                Hi,

                Confirm your email address to finish creating your Memes for Laughs account:

                %s

                The link works for %s. If you didn't sign up, ignore this email; no account will be created.
                """.formatted(link, hours(props.verifyLinkTtl().toHours())));
    }

    public void alreadyRegistered(String to, String resetLink) {
        send(to, "You already have a Memes for Laughs account", """
                Hi,

                Someone (hopefully you) tried to sign up with this email address, but it already has an account.
                Sign in instead. If you forgot your password, choose a new one here:

                %s

                If this wasn't you, ignore this email; nothing has changed.
                """.formatted(resetLink));
    }

    public void resetPassword(String to, String link) {
        send(to, "Choose a new Memes for Laughs password", """
                Hi,

                Choose a new password for your Memes for Laughs account:

                %s

                The link works for %s and only once. If you didn't ask for it, ignore this email; your password
                stays the same.
                """.formatted(link, hours(props.resetLinkTtl().toHours())));
    }

    private void send(String to, String subject, String text) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            log.warn("Email is not set up (spring.mail.host), so here is the email instead.\nTo: {}\nSubject: {}\n\n{}",
                    to, subject, text);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.mailFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mail.send(message);
        } catch (MailException e) {
            // Same answer to the visitor either way: a failed send must not reveal whether the account exists
            log.error("Could not send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }

    private static String hours(long h) {
        return h == 1 ? "1 hour" : h + " hours";
    }
}
