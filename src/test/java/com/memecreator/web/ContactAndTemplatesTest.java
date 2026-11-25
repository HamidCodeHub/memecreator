package com.memecreator.web;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.config.MemeProperties;
import com.memecreator.contact.ContactService;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.WebProperties;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.memecreator.web.TestAccounts.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The contact form (public, CSRF) and the templates page (signed-in). */
@WebMvcTest(controllers = {ContactController.class, TemplateGalleryController.class})
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class})
@TestPropertySource(properties = {"meme.web.open-dev-apis=false",
        "meme.security.api-keys-file=target/test-api-keys/contact-test.json"})
class ContactAndTemplatesTest {

    static final String MESSAGE = "{\"email\": \"a@example.com\", \"subject\": \"Hi\", \"message\": \"Love the site!\"}";

    @Autowired
    MockMvc mvc;

    @MockBean
    ContactService contact;

    @MockBean
    TemplateRepository templates;

    @MockBean
    AccountService accounts;

    @TempDir
    Path dir;

    @Test
    void anyoneCanWriteButOnlyWithTheCsrfToken() throws Exception {
        mvc.perform(post("/api/web/contact").contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
                .andExpect(status().isForbidden());
        verify(contact, never()).send(any(), any(), any(), any(), any());

        mvc.perform(post("/api/web/contact").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(MESSAGE))
                .andExpect(status().isAccepted());
        verify(contact).send(eq("a@example.com"), eq("Hi"), eq("Love the site!"), anyString(), isNull());
    }

    @Test
    void rejectsABadEmailAndIgnoresBots() throws Exception {
        mvc.perform(post("/api/web/contact").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nope\", \"subject\": \"Hi\", \"message\": \"Love the site!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/web/contact").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@example.com\", \"subject\": \"Hi\", \"message\": \"Buy pills\", "
                                + "\"website\": \"http://spam\"}"))
                .andExpect(status().isAccepted());
        verify(contact, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void templatesAreForSignedInPeople() throws Exception {
        Path image = Files.write(dir.resolve("01_Drake Hotline Bling.jpg"), new byte[] {1, 2, 3});
        when(templates.findAll()).thenReturn(List.of(MemeTemplate.fromPath(image)));

        mvc.perform(get("/api/web/templates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/web/templates").with(signedIn(7, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Drake Hotline Bling"))
                .andExpect(jsonPath("$[0].url").value("/api/web/templates/01/image"));
        mvc.perform(get("/api/web/templates/01/image").with(signedIn(7, "USER")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
        mvc.perform(get("/api/web/templates/99/image").with(signedIn(7, "USER")))
                .andExpect(status().isNotFound());
    }
}
