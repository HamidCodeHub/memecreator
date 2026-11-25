package com.memecreator.web;

import com.memecreator.account.AccountProperties;
import com.memecreator.account.MemeLibrary;
import com.memecreator.account.SharedMeme;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Public pages for shared memes. /s/&lt;token&gt; is the link people post: a small page with the image and the
 * preview tags (Open Graph, X cards) that make Facebook, X, WhatsApp and Telegram show the meme. No sign-in.
 */
@Hidden
@Controller
public class SharePageController {

    private static final String TOKEN = "{token:[A-Za-z0-9_-]{8,32}}";

    private final MemeLibrary library;
    private final AccountProperties props;

    public SharePageController(MemeLibrary library, AccountProperties props) {
        this.library = library;
        this.props = props;
    }

    static String base(String publicUrl) {
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @GetMapping(value = "/s/" + TOKEN + ".jpg")
    public ResponseEntity<FileSystemResource> image(@PathVariable String token) {
        return library.findShare(token)
                .flatMap(s -> library.imagePath(s.getFolder(), s.getFile()))
                .map(path -> ResponseEntity.ok()
                        .contentType(MediaTypeFactory.getMediaType(path.getFileName().toString())
                                .orElse(MediaType.IMAGE_JPEG))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                        .body(new FileSystemResource(path)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/s/" + TOKEN, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        return library.findShare(token)
                .map(s -> ResponseEntity.ok()
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .body(html(s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .body(notFound()));
    }

    private String html(SharedMeme s) {
        String base = base(props.publicUrl());
        String pageUrl = base + "/s/" + s.getToken();
        String imageUrl = pageUrl + ".jpg";
        String caption = s.getCaption() == null ? "" : s.getCaption().replace('\n', ' ').strip();
        String title = caption.isEmpty() ? s.getTemplate() + " meme" : caption;
        if (title.length() > 90) {
            title = title.substring(0, 89) + "…";
        }
        String alt = s.getTemplate() + " meme" + (caption.isEmpty() ? "" : ": " + caption);
        return PAGE
                .replace("{title}", esc(title))
                .replace("{pageUrl}", esc(pageUrl))
                .replace("{imageUrl}", esc(imageUrl))
                .replace("{alt}", esc(alt))
                .replace("{home}", esc(base + "/"));
    }

    private String notFound() {
        return NOT_FOUND.replace("{home}", esc(base(props.publicUrl()) + "/"));
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static final String STYLE = """
            <style>
              body{margin:0;min-height:100vh;display:grid;place-items:center;background:#e9ecf1;color:#1b1f2a;
                   font:17px/1.5 system-ui,sans-serif}
              main{max-width:min(640px,100vw - 32px);padding:24px 0;display:grid;gap:16px;justify-items:center}
              img{max-width:100%;height:auto;border-radius:4px;background:#fff}
              .brand{font:400 28px Impact,'Anton','Arial Narrow Bold',sans-serif;letter-spacing:.01em;color:#1b1f2a;
                     text-decoration:none}
              .cta{display:inline-block;padding:12px 20px;border:2px solid #1b1f2a;border-radius:10px;
                   background:#ffd23f;color:#1b1f2a;font-weight:700;text-decoration:none}
              p{margin:0;color:#5b6375}
            </style>""";

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>{title} · Memes for Laughs</title>
            <meta name="robots" content="noindex">
            <meta property="og:type" content="website">
            <meta property="og:site_name" content="Memes for Laughs">
            <meta property="og:title" content="{title}">
            <meta property="og:description" content="Made with Memes for Laughs. Type anything, get six memes.">
            <meta property="og:url" content="{pageUrl}">
            <meta property="og:image" content="{imageUrl}">
            <meta property="og:image:alt" content="{alt}">
            <meta name="twitter:card" content="summary_large_image">
            <meta name="twitter:title" content="{title}">
            <meta name="twitter:image" content="{imageUrl}">
            """ + STYLE + """
            </head>
            <body>
            <main>
              <a class="brand" href="{home}">Memes for Laughs</a>
              <img src="{imageUrl}" alt="{alt}">
              <p>Type anything, get six memes.</p>
              <a class="cta" href="{home}">Make your own</a>
            </main>
            </body>
            </html>
            """;

    private static final String NOT_FOUND = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Meme not found · Memes for Laughs</title>
            <meta name="robots" content="noindex">
            """ + STYLE + """
            </head>
            <body>
            <main>
              <a class="brand" href="{home}">Memes for Laughs</a>
              <p>This meme isn't shared anymore.</p>
              <a class="cta" href="{home}">Make your own</a>
            </main>
            </body>
            </html>
            """;
}
