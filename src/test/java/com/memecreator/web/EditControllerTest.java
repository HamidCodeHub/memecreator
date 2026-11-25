package com.memecreator.web;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.config.MemeProperties;
import com.memecreator.edit.MemeEditor;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.memecreator.web.TestAccounts.sessionCookie;
import static com.memecreator.web.TestAccounts.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing a meme's words. */
@WebMvcTest(EditController.class)
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class})
@TestPropertySource(properties = {"meme.web.open-dev-apis=false",
        "meme.security.api-keys-file=target/test-api-keys/edit-test.json"})
class EditControllerTest {

    static final String URL = "/generated/f/01_drake.jpg";
    static final String BODY = "{\"url\": \"" + URL + "\", \"texts\": [\"Bugs\", \"Features\"]}";

    @Autowired
    MockMvc mvc;

    @MockBean
    MemeEditor editor;

    @MockBean
    AccountService accounts;

    @Test
    void infoPreviewAndSaveForTheOwner() throws Exception {
        when(editor.info(7L, URL)).thenReturn(new MemeEditor.EditInfo(URL, "Drake", List.of("no", "yes"),
                List.of("a", "b"), List.of("a", "b"), URL));
        when(editor.preview(7L, URL, List.of("Bugs", "Features"))).thenReturn(new byte[] {9, 9});
        when(editor.save(7L, URL, List.of("Bugs", "Features")))
                .thenReturn(new MemeEditor.Edited("/generated/f/01_drake-v2.jpg", "Drake", List.of("Bugs", "Features")));

        mvc.perform(get("/api/web/edits").param("url", URL).with(signedIn(7, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[1]").value("yes"));
        mvc.perform(post("/api/web/edits/preview").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[] {9, 9}));
        mvc.perform(post("/api/web/edits").with(signedIn(7, "USER")).with(sessionCookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/generated/f/01_drake-v2.jpg"));
    }

    @Test
    void needsSignInAndCsrfAndSaysWhenAMemeCantBeEdited() throws Exception {
        mvc.perform(get("/api/web/edits").param("url", URL)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/web/edits").with(signedIn(7, "USER")).with(sessionCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(editor, never()).save(anyLong(), any(), any());

        when(editor.info(7L, URL)).thenThrow(new MemeEditor.NotEditableException());
        mvc.perform(get("/api/web/edits").param("url", URL).with(signedIn(7, "USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_EDITABLE"));
    }
}
