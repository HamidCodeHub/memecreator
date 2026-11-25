package com.memecreator.web;

import com.memecreator.account.ApiProperties;
import com.memecreator.account.MemeSetRepository;
import com.memecreator.account.PromptQuota;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.AccountService;
import com.memecreator.account.MemeSet;
import com.memecreator.account.UserAccount;
import com.memecreator.config.MemeProperties;
import com.memecreator.photo.CaptionStyle;
import com.memecreator.photo.Elaboration;
import com.memecreator.photo.PhotoMemeService;
import com.memecreator.photo.PhotoMemeService.PhotoMeme;
import com.memecreator.photo.PhotoMemeService.PhotoMemeBatch;
import com.memecreator.photo.PhotoPlan;
import com.memecreator.security.ApiKeyStore;
import com.memecreator.security.GenerationLimiter;
import com.memecreator.security.SecurityConfig;
import com.memecreator.security.SecurityProperties;
import com.memecreator.security.WebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.memecreator.web.TestAccounts.sessionCookie;
import static com.memecreator.web.TestAccounts.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Use your own photo" in the web app. */
@WebMvcTest(WebPhotoController.class)
@Import({SecurityConfig.class, GenerationLimiter.class, ApiKeyStore.class, PromptQuota.class})
@EnableConfigurationProperties({MemeProperties.class, WebProperties.class, SecurityProperties.class,
        AccountProperties.class, ApiProperties.class})
@TestPropertySource(properties = {"meme.web.open-dev-apis=false",
        "meme.security.api-keys-file=target/test-api-keys/photo-test.json"})
class WebPhotoControllerTest {

    static final MockMultipartFile PHOTO = new MockMultipartFile("image", "cat.jpg", "image/jpeg", new byte[] {1, 2});

    @Autowired
    MockMvc mvc;

    @MockBean
    MemeSetRepository memeSets;

    @MockBean
    PhotoMemeService photoMemes;

    @MockBean
    AccountService accounts;

    @BeforeEach
    void setUp() {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(7L);
        when(accounts.requireCurrentTerms(7L)).thenReturn(account);
    }

    @Test
    void makesMemesFromThePhotoWithOrWithoutATopic() throws Exception {
        when(photoMemes.create(any(), isNull(), eq("Italian"), eq(6), eq(Elaboration.NORMAL)))
                .thenReturn(new PhotoMemeBatch(null, "Italian", Elaboration.NORMAL, 1, PhotoPlan.Analysis.EMPTY,
                        "E:/out/20261123-001000_photo", "/generated/20261123-001000_photo/00_upload.jpg",
                        List.of(new PhotoMeme(CaptionStyle.TOP_BOTTOM, List.of("a", "b"),
                                "/generated/20261123-001000_photo/01_top-bottom.jpg", "E:/out/x.jpg"))));

        mvc.perform(multipart("/api/web/photo-memes").file(PHOTO).param("language", "Italian")
                        .with(signedIn(7, "USER")).with(sessionCookie()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memes[0].url").value("/generated/20261123-001000_photo/01_top-bottom.jpg"))
                .andExpect(jsonPath("$.memes[0].template").value("Your photo"))
                .andExpect(jsonPath("$.memes[0].file").doesNotExist());
        verify(accounts).recordMemeSet(eq(7L), eq("E:/out/20261123-001000_photo"), any(), eq(MemeSet.Source.WEB));
    }

    @Test
    void needsSignInTheCsrfTokenAndAPhoto() throws Exception {
        mvc.perform(multipart("/api/web/photo-memes").file(PHOTO).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/web/photo-memes").file(PHOTO).with(signedIn(7, "USER")).with(sessionCookie()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/web/photo-memes")
                        .file(new MockMultipartFile("image", "notes.txt", "text/plain", new byte[] {1}))
                        .with(signedIn(7, "USER")).with(sessionCookie()).with(csrf()))
                .andExpect(status().isBadRequest());
        verify(photoMemes, never()).create(any(), any(), any(), anyInt(), any());
    }
}
