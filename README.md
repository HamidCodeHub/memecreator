# Memes for Laughs (memecreator)

Spring Boot app that turns a topic string into memes: it asks your AI gateway to pick
templates from `E:\photos\meme` and write captions, then draws the captions (classic white
text with black outline) onto 4 templates and saves them.

## Run

Requirements: JDK 17+ and Maven.

```bat
# point it at your gateway (defaults: http://localhost:8080, no key, gpt-4o-mini)
set AI_GATEWAY_URL=http://localhost:8080
set AI_GATEWAY_API_KEY=your-key
set AI_GATEWAY_MODEL=gpt-4o-mini

mvn spring-boot:run
```

Open http://localhost:8081, type a topic, click **Make memes**.

Or from the command line (memes are generated on startup, then the server keeps running):

```bat
mvn spring-boot:run -Dspring-boot.run.arguments="--topic=deploying on a friday"
```

## API

Swagger UI: http://localhost:8081/swagger-ui.html (OpenAPI JSON at `/v3/api-docs`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/memes` | Body: `{"topic":"...", "language":"English", "strategy":"AI_PICK" \| "RANDOM", "templates":["Drake Hotline Bling", ...]}` — only `topic` is required |
| GET | `/api/memes?topic=...&language=Italian&strategy=RANDOM&templates=Two Buttons,This Is Fine` | Same thing via query string |
| POST | `/api/memes/from-image` | Memes from **your own photo** (multipart form, see below) |
| GET | `/api/templates` | Lists the templates with their text boxes |
| GET | `/api/templates/preview?name=Drake Hotline Bling` | PNG of the template with its text boxes outlined and numbered |
| POST | `/api/memes/render` | `{"template":"Drake Hotline Bling","texts":["...","..."]}` — render your own texts, no AI call |
| GET | `/api/ai/models` | Passes through the gateway's `GET /v1/models` — pick a model id from here |
| GET | `/generated/<folder>/<file>.jpg` | Serves a generated meme |

Strategies:
- `AI_PICK` (default): the AI chooses the 4 templates that fit the topic best.
- `RANDOM`: 4 random templates; the AI only writes captions.
- Passing `templates` uses exactly those (names are matched loosely, e.g. `drake hotline bling`).

Example response:

```json
{
  "topic": "deploying on a friday",
  "language": "English",
  "folder": "E:\\Work\\memecreator\\output\\20261115-113000_deploying-on-a-friday",
  "memes": [
    {
      "template": "This Is Fine",
      "texts": ["Friday 5:58 PM deploy", ""],
      "url": "/generated/20261115-113000_deploying-on-a-friday/01_this-is-fine.jpg",
      "file": "E:\\Work\\memecreator\\output\\20261115-113000_deploying-on-a-friday\\01_this-is-fine.jpg"
    }
  ]
}
```

## Using Groq directly (gpt-oss-120b)

A `groq` profile skips your gateway and calls Groq's OpenAI-compatible API:

```bat
set GROQ_API_KEY=gsk_...
mvn spring-boot:run -Dspring-boot.run.profiles=groq
```

It uses `openai/gpt-oss-120b` with `reasoning-effort: low` (fast, and enough thinking for jokes),
JSON mode on, and `max-tokens: 1536` (reasoning tokens share the output budget, and Groq's free tier counts
prompt + `max_tokens` against 8,000 tokens per minute).
See `src/main/resources/application-groq.yml`. In IntelliJ set *Active profiles* to `groq`.

## AI gateway contract

The app sends an OpenAI-compatible chat completion:

```
POST {ai-gateway.base-url}{ai-gateway.chat-path}      (default /v1/chat/completions)
Authorization: Bearer {ai-gateway.api-key}             (only if a key is set)
{"model": "auto/caption", "temperature": 1.0, "max_tokens": 1536,
 "response_format": {"type": "json_object"},
 "messages": [{"role":"system","content":"..."}, {"role":"user","content":"..."}]}
```

Photo memes use `"model": "auto/vision"` (`ai-gateway.vision-max-tokens`, default 1536) and send the photo as a
data URI, never as an http link (aiproxy's Gemini adapter rejects links), in aiproxy's image part
(`ai-gateway.image-format: aiproxy`; aiproxy rejects OpenAI's `image_url` part, which `openai` sends for Groq):

```
{"role":"user","content":[{"type":"text","text":"..."},
                          {"type":"image","url":"data:image/jpeg;base64,..."}]}
```

Every call asks for JSON (`response_format` json_object, and the prompt says so too). The reply is trimmed,
```` ``` ```` fences and `<think>` blocks are removed, and if it still isn't valid JSON the call fails: memecreator
never makes up placeholder captions.

It reads the reply text from `choices[0].message.content`, and also accepts Anthropic-style
`content[].text` or a plain `output_text` / `text` field. If your gateway uses another path,
change `ai-gateway.chat-path` in `application.yml`.

## Memes from your own photo

```bat
curl -F "image=@C:\photos\boss.jpg" -F "count=4" -F "elaboration=NORMAL" http://localhost:8081/api/memes/from-image
```

| Field | Required | Default | Meaning |
|---|---|---|---|
| `image` | yes | | JPEG or PNG (GIF/BMP also work), up to 10 MB |
| `topic` | no | | steer the joke; leave it out and the AI decides what is funny about the photo |
| `language` | no | English | caption language |
| `count` | no | 4 | how many memes from the photo (1-8) |
| `elaboration` | no | NORMAL | `NORMAL` = **1 AI call** (the vision model reads the photo and writes the captions); `HIGH` = **2 AI calls** (the vision model describes the photo in detail, then the text model writes the captions; usually funnier) |

The AI picks a caption style per meme: `TOP_BOTTOM`, `TOP`, `BOTTOM` (white outlined text over the photo) or
`CAPTION_BAR` (a white bar with black text added above the photo, so faces are never covered). The response
includes `analysis` (what the AI saw), `aiCalls`, and the URL of every meme. The photo is shrunk to 1024 px
before it is sent to the AI, which keeps the request inside Groq's limits.

Captions never cover a face. Before drawing, memecreator collects the areas to keep clear: the heads found by the
local face detector (YuNet, score 0.5+, each face box grown to the whole head) and the boxes the vision model lists
under `analysis.keep_clear` (`[ymin, xmin, ymax, xmax]` on a 0-1000 scale: faces of people, animals, statues and
drawings, and details the jokes depend on; boxes over half the photo are ignored). Top text then only uses the space
above the highest area, bottom text the space below the lowest. When that space is too small for readable text, the
caption goes into a white bar above (top text) or below (bottom text) the photo instead. With nothing to keep clear,
captions go where they always did. The areas are saved next to the upload (`00_keep-clear.json`), so edited memes are
placed the same way.

Needs a vision model: `ai-gateway.vision-model` (default `auto/vision`, aiproxy's virtual vision model; override
with `AI_GATEWAY_VISION_MODEL`, e.g. a fixed `groq/qwen/qwen3.8-27b`).

### How long memes are kept

Memes made by accounts (web app and personal API keys) stay on the server for `meme.retention.keep-for` (1 hour):
time to heart, share, download or edit them. After that, `MemeRetention` (every `check-every`, 5 minutes) deletes
every file of the set that is not a favorite, a shared meme (`/s/...` links keep working) or the AI's original of
one of them. The uploaded photo (`00_upload.jpg`) stays only while one of its memes is a favorite, because the
editor re-draws photo memes from it; un-hearting the last one deletes it at the next check. Empty folders are
removed; the set's row stays (the daily quotas count it). Folders not made by an account (face photos, admin/dev
memes) are not touched. Set `meme.retention.enabled: false` to keep everything until the account is deleted.

## Memes with your face

Your face is pasted onto the faces of photo templates (46 templates, 65 face slots; cartoons are excluded).
Everything runs locally with OpenCV; **your photo is never sent to the AI**, except with the separate AI face
endpoints below (`/api/memes/with-face/ai...`), which send the cut-out head to the image model.

How a face is pasted:

1. **Detect** the face in your photo (YuNet, MIT licence, bundled).
2. **Map** it and each template face with the 468-point MediaPipe face mesh (Apache-2.0, bundled, see
   `src/main/resources/models/README.md`).
3. **Direction:** if your photo looks one way and the template face the other, your photo is mirrored first, so the
   pasted face turns the same way as the template's.
4. **Paste**, in one of four styles (`faceStyle`):
   - `HEAD` (default): the classic meme cut-out. Your whole head (face, hair, ears, hat) is cut out of your photo
     with person segmentation (BodyPix, Apache-2.0, bundled), refined by colour with GrabCut (so a light wall next
     to a bald head stays behind) and limited to a head-shaped area, cut off below the chin along your jaw. The
     template's own head is cut out the same way and removed; your head is pasted over it, turned to match,
     mirrored if needed, at least 10% bigger and up to 40% bigger when that is needed to cover the old head
     (`MAX_COVER_SCALE`); whatever is still uncovered is filled in from the background (inpainting). No blending:
     obvious on purpose. Use a photo that shows your whole head with some space around it (a head cut off by the
     photo's edge gets a straight edge).
   - `FULL`: as much of you as possible. Like `BOLD`, plus the face is a bit bigger than the template's
     and the paste reaches past the face outline, up the forehead towards the hairline and out at the cheeks and
     jaw, in mostly your own skin tone. The least realistic: on a strongly turned or small template head your face
     can overhang it.
   - `BOLD`: clearly you. Your face keeps its own shape (chin, jaw, face width); the template face only
     decides where it goes, its size, tilt and direction. It covers the template's chin and face outline and keeps
     most of your own skin tone, with a soft edge. Less realistic, more recognisable.
   - `NATURAL`: your face is warped onto the template face's own shape and blended in with seamless (Poisson)
     cloning, so it takes the template's skin tone and lighting. More realistic, less recognisable.

Faces the mesh can't read (the cat, a cartoon, the back of a head) fall back to the simpler 5-point paste.
A sharp, well-lit, roughly front-facing photo works best. When your photo and a template face look in very
different directions (e.g. a profile photo on a frontal face), the meme still renders but gets a `warnings` entry.

**Templates with several people** list their `characters` (e.g. Anakin Padme: `anakin` = panels 1 and 3, `padme` =
panels 2 and 4). Your face goes on one of them, chosen in this order:

1. `slots=2&slots=4`: exactly these face slots;
2. `character=padme`: that person;
3. otherwise, with AI captions, the AI picks the person whose role fits its joke (same call, no extra cost);
4. otherwise the template's default (the first one listed).

Templates where every face is the same person (Harold, Drake, the clown) get your face on all of them.
`GET /api/templates` lists each template's `characters` with their slots; the preview labels the faces
(`F2 padme`). Each meme in the response says where your face went (`face`, `slots`) and any `warnings`.

| Method | Path | AI calls | What it does |
|---|---|---|---|
| POST | `/api/memes/with-face` | 1 | AI picks face templates (or use `templates`) and writes captions; optional `topic` |
| POST | `/api/memes/with-face/render` | 0 | one `template`, your own `texts` (or none: just the face) |
| GET | `/api/faces/{faceId}` | | download your stored photo |
| DELETE | `/api/faces/{faceId}` | | delete your photo and every meme made from it |

Common fields (multipart form): `face` (photo) **or** `faceId` (reuse an earlier upload); with a new photo also
`confirmAdult=true` and `confirmConsent=true` (18+, and it's your face or you have permission). Optional
`character` or `slots` (see above; the template preview shows the face numbers in blue) and `faceStyle`
(`HEAD`, `FULL`, `BOLD` or `NATURAL`).

```bat
curl -F "face=@C:\me.jpg" -F confirmAdult=true -F confirmConsent=true -F "topic=working in IT" http://localhost:8081/api/memes/with-face
curl -F "faceId=<id from the first call>" -F "template=Hide the Pain Harold" -F "texts=When prod is down" -F "texts=and it's Friday" http://localhost:8081/api/memes/with-face/render
curl -F "faceId=<id>" -F "template=Anakin Padme 4 Panel" -F "character=padme" http://localhost:8081/api/memes/with-face/render
```

### AI face replacement (`/api/memes/with-face/ai`)

Same cut-out as `HEAD` (your head is cut out of your photo locally), but the **pasting is done by an image
model**: for every face slot, the area around the template face (3 face sizes, kept inside its panel) and your
cut-out head on white are sent to the model, which redraws the area with your head in place, in the template's
pose, lighting and style. The answer is scaled back, blended in with a soft edge and clipped to the panel; the
captions are drawn on top afterwards (the model never writes text).

| Method | Path | AI calls | What it does |
|---|---|---|---|
| POST | `/api/memes/with-face/ai` | 1 + 1 per face slot | like `/api/memes/with-face` (AI captions), faces pasted by AI |
| POST | `/api/memes/with-face/ai/render` | 1 per face slot | like `/api/memes/with-face/render` (your own texts) |

Same fields as the other face endpoints, without `faceStyle`. A 4-panel Anakin with `character=padme` is 2 image
calls. Each call takes 10-30 s and costs about $0.04 with `gemini/gemini-2.5-flash-image` (image models are not
in Gemini's free tier); `aiCalls` in the response counts them.

**Privacy:** this is the only place where (part of) the user's photo leaves the server: the cut-out head and the
template area go to the image provider through the gateway. The user's existing consent (`confirmAdult`,
`confirmConsent`) covers it; use a paid provider tier, whose terms don't allow training on the data, and list the
provider as a processor in the privacy policy. The endpoints stay off until an image model is set.

Configuration (`ai-gateway`):

- `image-model` (env `AI_GATEWAY_IMAGE_MODEL`, default `gemini/gemini-2.5-flash-image`): a model that **outputs
  images**. Groq has none (`groq/qwen/qwen3.8-27b` reads images but only answers in text). Set it empty to turn the
  AI endpoints off (they then answer 500 with "AI face replacement is off").
- `image-edit-size` (default 1024): longest side of the area sent to the model.
- `image-provider-options`: extra `provider_options` for the request. For `gemini/` models the client already
  sends `{"gemini": {"generationConfig": {"responseModalities": ["TEXT", "IMAGE"]}}}`.

What the gateway (aiproxy) must do:

1. Pass image parts **back**: `GeminiAdapter.parseResponse` must turn candidate parts with `inlineData` /
   `inline_data` (`mimeType`, `data`) into image content parts (`{"type": "image", "url": "data:<mime>;base64,<data>"}`),
   next to the text parts, and not fail when an answer has an image but no text.
2. Route `gemini/gemini-2.5-flash-image` with image input (register it, or allow unregistered models).
3. Have `GEMINI_API_KEY` set (paid tier).
4. Accept requests of ~2 MB (two base64 JPEGs), answers of ~3 MB, and wait at least 60 s for the provider.

The client also understands OpenAI-style answers (`image_url` parts) and OpenRouter's `message.images`, so other
image models work as long as the gateway passes the image back as a data URI.

**Privacy (GDPR):** face photos are stored in `meme.private-dir` (never served over HTTP, never used as
templates), re-encoded so EXIF data such as GPS location is dropped, with the upload time and the user's
confirmations. They are deleted automatically after `meme.face-retention` (default 90 days), together with the
memes made from them; memes with faces go to unguessable `face-<uuid>` folders. Face slots in
`template-layouts.yml` were found with the same detector and checked by hand. Nothing is inferred from the photo
(no age, gender or similar); the character is chosen from the joke or by the user.

## Text boxes per template (`src/main/resources/template-layouts.yml`)

Each template can define where its captions go and what each one means, e.g. Drake's two texts sit in the
white column (rejected / approved), Two Buttons gets a label per button plus the sweating guy:

```yaml
layouts:
  "Drake Hotline Bling":
    boxes:                 # [x, y, width, height, color, valign, "hint"], fractions of the image
      - [0.52, 0.02, 0.46, 0.46, black, middle, "the thing Drake rejects"]
      - [0.52, 0.52, 0.46, 0.46, black, middle, "the thing Drake approves"]
```

- `color`: `white` = white text with black outline (photos), `black` = plain black text (white areas).
- The AI sees every hint and returns one text per box (`"texts": [...]` in the response).
- 66 of the 100 templates have a layout; the rest use the classic top/bottom captions (`default:`).
- Two templates that are the same meme: delete the extra file, or list it under `duplicates:` (`"Drake Hotline Bling": ["Drake Blank"]`) to hide it from the AI, random picks and the gallery.
- Face slots: `faces:` lists `[x, y, w, h, then 5 landmark x,y pairs]` per face. On templates with several
  people, add `characters:` (name: description, the first one is the default) and the character's name as a 15th
  value on each face, e.g. `- [0.13, 0.11, ..., anakin]`.
- To add or tune one: edit the file, rebuild (Ctrl+F9 in IntelliJ copies it to `target/classes`; the app
  re-reads it without a restart), and open `/api/templates/preview?name=<template>` to see the boxes.

## Web app (repository `memecreator-fe`)

The site lives in its own repository, [memecreator-fe](https://github.com/HamidCodeHub/memecreator-fe) (clone it next to this one, E:\Work\memecreator-fe). It is a
React + TypeScript app (Vite): sign in with Google or Facebook (Meta), type what the memes should be about,
get 6 memes with download links. The backend does the whole sign-in (Spring Security OAuth2 login); the browser
only gets the session cookie, never a token.

### 1. Credentials

| Provider | Where | Redirect URI to register |
|---|---|---|
| Google | Google Cloud Console → APIs & Services → Credentials → OAuth client ID (Web application) | `http://localhost:5173/login/oauth2/code/google` |
| Meta | developers.facebook.com → your app → add **Facebook Login** → Settings → Valid OAuth Redirect URIs | `http://localhost:5173/login/oauth2/code/facebook` |

Then set them as environment variables for Spring Boot (IntelliJ: Run configuration → Environment variables):

```
GOOGLE_CLIENT_ID=...    GOOGLE_CLIENT_SECRET=...
META_APP_ID=...         META_APP_SECRET=...
```

A provider without credentials still shows its button, marked "Not set up yet". For production, register
`https://<your-domain>/login/oauth2/code/google` and `.../facebook` too. Google and Meta ask for their own button
artwork (logos) in their branding guidelines; the buttons here are text-only, so add the official assets before
launch.

### Email + password accounts

Next to Google and Facebook, people can create an account with their email and a password (10–64 characters,
stored as a bcrypt hash). The address is confirmed by a link (valid 24 h); "Forgot password?" emails a reset link
(valid 1 h). Answers never reveal whether an address has an account. Repeated wrong passwords and email requests are
slowed down per address and per IP.

- **Database:** accounts live in an H2 file database, `data/memecreator.mv.db` (`spring.datasource.url`); tables
  are created by Flyway from `src/main/resources/db/migration`. For PostgreSQL later only the URL, user and password
  change (plus the `flyway-database-postgresql` dependency).
- **Emails:** without SMTP settings nothing is sent; the email, link included, is **written to the backend log** —
  copy the link from there when trying sign-up locally. To send real email set `SPRING_MAIL_HOST`,
  `SPRING_MAIL_PORT` (587), `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`,
  `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` and
  `MEME_MAIL_FROM`. Links point to `meme.accounts.public-url` (`MEME_PUBLIC_URL`, default `http://localhost:5173`).
- **One account, several ways in:** a Google or Facebook sign-in with the same confirmed email joins the existing
  account. If that account's email was never confirmed, its password is removed (someone else may have signed up
  with the address first).

### 2. Run it

```bash
# terminal 1: backend on 8081 (and aiproxy on 8080)
mvn spring-boot:run

# terminal 2: front end on 5173, in the memecreator-fe repository
cd ../memecreator-fe
npm install        # first time only
npm run dev        # open http://localhost:5173
```

The dev server forwards `/api`, `/oauth2`, `/login`, `/generated` and `/s/` (shared memes) to the backend, so the browser sees one site
and the provider redirects back to port 5173. Open the app at `http://localhost:5173`, not 8081.

### 3. What the backend adds

| Endpoint | |
|---|---|
| `GET /oauth2/authorization/google` · `/facebook` | starts sign-in (the buttons link here) |
| `GET /api/web/providers` | public: sign-in options and whether each has credentials |
| `GET /api/web/me` | the signed-in user (401 when signed out) |
| `POST /api/web/memes` `{"topic": "...", "language": "Italian", "template": "01"}` | 6 memes (AI picks templates, or all on the optional `template`); one prompt of the daily allowance (429 `DAILY_LIMIT` when used up), 429 after `meme.web.max-sets-per-hour`; 403 `TERMS_NOT_ACCEPTED` until the current terms are accepted; 503 `AI_UNAVAILABLE` when the AI gateway fails |
| `GET /api/web/plan` | the plan (FREE/PREMIUM), today's prompts used and left, when they reset, and `upgradeAvailable: false` while Premium can't be bought |
| `GET /api/web/legal` | public: current terms version and the URLs of the legal pages |
| `POST /api/web/terms/accept` `{"version": "2026-11-22"}` | records that the user accepted this version (409 if it is no longer current) |
| `DELETE /api/web/account` `{"confirm": "DELETE"}` | deletes the account, its sign-in links and its memes, then signs out (204) |
| `GET/POST /api/web/favorites`, `DELETE /api/web/favorites/{id}` | the heart: favorites shown on the profile page |
| `POST /api/web/shares` `{"url": "/generated/..."}` | public link for one of your memes: `/s/<token>` (page with preview tags) and `/s/<token>.jpg` |
| `GET/POST /api/web/api-keys`, `DELETE /api/web/api-keys/{id}` | personal API keys (the full key is returned once) and today's API usage |
| `POST /api/web/photo-memes` (multipart: `image`, optional `topic`, `language`) | 6 memes from the user's own photo; without a topic the AI decides what's funny. Counts toward the hourly limit; the upload is kept only while one of its memes is a favorite (see "How long memes are kept"), never used as a template |
| `GET /api/web/templates`, `GET /api/web/templates/{id}/image` | the templates page: every template, blank, to view and download |
| `POST /api/web/contact` `{"email", "subject", "message"}` | public, CSRF token needed, 5 per hour per IP and per email: emails the message to `meme.security.admin-emails` with Reply-To set to the sender (logged when email isn't set up) |
| `GET /api/web/edits?url=/generated/...` | what the Edit dialog needs: the box labels, the current words and the AI's words (404 `NOT_EDITABLE` for memes made before editing existed or not yours) |
| `POST /api/web/edits/preview` `{"url", "texts": [...]}` | the meme redrawn with new words, as a JPEG; nothing is saved (300 per hour) |
| `POST /api/web/edits` `{"url", "texts": [...]}` | saves the new words as a new version (`<name>-v2.jpg`, ...); the AI's original is kept. Sending the AI's words back returns the original (60 per hour) |
| `POST /api/web/logout` | signs out (204) |

`/api/web/**` needs a signed-in user and a CSRF token (the app sends the `XSRF-TOKEN` cookie back as
`X-XSRF-TOKEN`). The other APIs need an API key or an admin sign-in — see "Security".

### 4. Production

`npm run build` in `memecreator-fe` writes the static site to its `dist` folder. Serve it from a web server that forwards the same
paths to Spring Boot, keeps the `Host` header and sets `X-Forwarded-Proto`, e.g. nginx:

```nginx
location / { root /srv/memecreator/dist; try_files $uri /index.html; }
location ~ ^/(api|oauth2|login|generated|s)/ {
    proxy_pass http://127.0.0.1:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

and in `application.yml`: `server.forward-headers-strategy: framework` and
`server.servlet.session.cookie.secure: true`. Before launch: have the legal pages reviewed (see below), and
note that generated memes can be opened by anyone signed in who has their URL.

The site lives at **https://memesforlaughs.com**. When it goes online:

- set `MEME_PUBLIC_URL=https://memesforlaughs.com` (email links and share links point there);
- Google Cloud console → the OAuth client: add `https://memesforlaughs.com` to the JavaScript origins and
  `https://memesforlaughs.com/login/oauth2/code/google` to the redirect URIs; publish the consent screen;
- Meta developer console → Facebook Login: add `https://memesforlaughs.com/login/oauth2/code/facebook` as a valid
  redirect URI, set the App domain, the Privacy Policy URL (`/privacy.html`) and the data deletion URL
  (`/data-deletion.html`), then switch the app to Live;
- send email from an address on the domain (e.g. `no-reply@memesforlaughs.com` through Brevo or similar) instead
  of the Gmail account.

**Watermark:** every generated meme gets `memesforlaughs.com` in a corner that has no caption (bottom-right first),
as white text on a translucent dark pill. When every corner has a caption (classic top + bottom text) a thin dark
strip with the name is added under the picture instead, so captions are never covered. Uploaded photos are never
marked. Settings: `meme.watermark.enabled` / `meme.watermark.text`.

### 5. Daily prompts, Premium and AI errors

- **Daily prompts:** every meme set made on the site (from a topic or a photo) is one prompt. FREE accounts get
  `meme.web.free-prompts-per-day` (10) a day, PREMIUM accounts `meme.web.premium-prompts-per-day` (0 = no limit). The
  day resets at midnight in `meme.api.quota-zone` (Europe/Rome). Failed sets don't count. Change the numbers in the
  git-ignored `config/application.yml` (or with `MEME_WEB_FREEPROMPTSPERDAY` / `MEME_WEB_PREMIUMPROMPTSPERDAY`) and
  restart:

  ```yaml
  meme:
    web:
      free-prompts-per-day: 10
      premium-prompts-per-day: 0
  ```

  The personal API keys keep their own limit (`meme.api.free-sets-per-day`).
- **Premium:** the site shows how many prompts are left, a "That's your 10 prompts for today" card at the limit, and
  a Premium page (`/premium`) whose upgrade button says "Coming soon": payments aren't open. During the alpha an admin
  moves testers between plans:

  ```http
  PUT /api/admin/accounts/plan
  {"email": "friend@example.com", "plan": "PREMIUM"}
  ```

- **When the AI gateway fails** (aiproxy down or timing out, a provider error, an unusable answer) people on the site
  and the public API get 503 `AI_UNAVAILABLE` with "The server isn't responding right now. Please try again later.";
  the real reason is only logged. The developer and admin APIs still answer 502 with the details. If aiproxy
  silently falls back to one of its `mock/...` models, that counts as a failure too, unless a mock model was asked
  for on purpose.

### 6. Profile, favorites, sharing and personal API keys

- **Header and footer** on every page. The name at the top right opens the **profile** (`/profile`): favorites,
  API keys and "Delete my account". The footer has the legal links and the Memes for Laughs Facebook, X and Instagram
  pages — set the real profile URLs in `memecreator-fe/src/site.ts`.
- **Favorites:** the heart under each meme. Only memes the account made can be favorited (`meme_set` records who
  made which folder). Up to 500 per account.
- **Sharing:** the share button asks for a public link (`/s/<token>`, 20 random characters) and offers the phone's
  share sheet (the image itself, so Instagram and WhatsApp work), Facebook, X, WhatsApp, Telegram and "Copy link".
  The page at `/s/<token>` is public and carries Open Graph / X card tags, so posts show the meme. The preview only
  works once the site is online: Facebook and X can't read `localhost`.
- **Personal API keys:** people create up to 5 keys on their profile (`mcu_...`, shown once, stored as a SHA-256
  hash). With `X-API-Key: mcu_...` they can call only `/api/v1/**`:

  | Endpoint | |
  |---|---|
  | `POST /api/v1/memes` `{"topic": "...", "language": "Italian"}` | 6 memes; counts toward the daily limit (`X-RateLimit-Limit` / `-Remaining` headers, 429 + `Retry-After` after it) |
  | `GET /api/v1/quota` | today's usage |
  | `GET /generated/...` | the images, with the same header |

  The limit is `meme.api.free-sets-per-day` (5) for FREE accounts and `premium-sets-per-day` for PREMIUM
  (`user_account.plan`; nothing sells premium yet). It is counted from the database, so restarts don't reset it,
  and resets at midnight in `meme.api.quota-zone` (Europe/Rome). Service keys (`mck_`, footballmeme) are unchanged.

### 7. Editing memes, logo and dark mode

- Every meme made from now on stores its recipe (template or photo, box texts and the AI's texts) in the `meme`
  table (Flyway `V4__meme_recipes.sql`). The pencil under a meme opens **Edit the words**: one field per text box,
  a live preview drawn by the server (same font, fitting and watermark), and Save, which writes a new version next
  to the original. Memes made before this update have no recipe, so they can't be edited.
- The logo (`memecreator-fe/public/logo.svg`, inlined in `src/Logo.tsx`) is the home button in the header and appears in
  the footer; `favicon.svg` is its icon part.
- Dark mode: the moon/sun button in the header. The site follows the system setting until the user picks one; the
  choice is kept in `localStorage` (`theme`). The legal pages follow it too and print in light colours.

### 8. Terms, privacy and account deletion

- **Pages:** `memecreator-fe/public/terms.html`, `privacy.html` and `data-deletion.html` (plain HTML, styled by
  `legal.css`; open them at `http://localhost:5173/privacy.html`). They are **drafts for legal review**: yellow
  notes mark what a lawyer should check or complete (address, hosting provider, retention of logs and backups,
  consumer-law clauses...). Remove the notes and the yellow banner before launch.
  Use `/privacy.html` as the Privacy Policy URL and `/data-deletion.html` as the data deletion instructions URL in
  the Google and Meta consoles.
- **Proof of consent:** each account stores the version it accepted and when (`user_account.terms_version`,
  `terms_accepted_at`). Email sign-ups tick a box on the form; Google/Facebook sign-ins see a "One step before your
  first memes" screen after signing in.
- **Changing the terms:** edit the pages, put the new date in them and in `meme.accounts.terms-version`
  (`application.yml`). At their next visit everyone is asked to accept the new version; making memes answers 403
  until they do.
- **Deletion:** "Delete my account" (profile page, or on the terms screen) asks the person to type DELETE.
  The account row goes, and with it (database cascade) its Google/Facebook links, email links, favorites, share
  links, API keys and the list of meme folders it made; those folders are then deleted from `meme.output-dir`. Email sign-ups never confirmed are
  deleted after `meme.accounts.unconfirmed-ttl` (7 days) by the nightly clean-up.

## Security

Two ways in, to the same endpoints:

- **Programs** (footballmeme, scripts) send an API key in the `X-API-Key` header. No session, no CSRF.
- **People** sign in with Google or Facebook (session cookie). Everyone is a USER; people whose email is in
  `meme.security.admin-emails` (env `MEME_ADMIN_EMAILS`, comma-separated) are also ADMIN.

| | no credentials | USER (signed in) | SERVICE key | ADMIN (key or sign-in) |
|---|---|---|---|---|
| `GET /api/web/providers`, sign-in | ✓ | ✓ | ✓ | ✓ |
| `/api/web/**` (the React app) | 401 | ✓ | 403 | ✓ (signed in) |
| `/api/memes`, `/api/memes/render`, `/api/templates`, `/api/templates/preview` | 401 | 403 | ✓ | ✓ |
| `/generated/**` (meme images) | 401 | ✓ | ✓ | ✓ |
| photo/face endpoints, `/api/faces/**`, harvest, `/api/ai/models`, `/api/admin/**`, Swagger, `/` | 401 | 403 | 403 | ✓ |

A wrong or revoked key is always 401. `meme.web.open-dev-apis: true` brings back the old development mode, where
only `/api/web/**` and `/api/admin/**` are protected.

### API keys

Keys are issued by you; memecreator stores only their SHA-256 hash in `private/api-keys.json` and shows each key
once. One key per client; issuing again replaces (rotates) the old one at once; the running app notices changes
to the file by itself.

```text
# IntelliJ: run com.memecreator.security.ApiKeyTool with these program arguments (working dir = project)
issue footballmeme              # role SERVICE (default)
issue hamid-admin ADMIN         # for Swagger / curl as admin, before Google sign-in is set up
list
revoke footballmeme
```

or, from a build: `java -cp target/memecreator-0.0.1-SNAPSHOT.jar -Dloader.main=com.memecreator.security.ApiKeyTool org.springframework.boot.loader.launch.PropertiesLauncher issue footballmeme`.

Signed in as admin (or with an ADMIN key) the same works over HTTP: `GET/POST /api/admin/api-keys`,
`DELETE /api/admin/api-keys/{client}`.

Give the key to the client as a secret — footballmeme reads it from the `MEMECREATOR_API_KEY` environment
variable. Never put keys in code, git or URLs, and use HTTPS once memecreator runs on another machine.

**Swagger:** "Authorize" → paste an ADMIN key. Signed in as admin in the browser works too.
The old test page at `/` is admin-only now and its buttons don't send CSRF tokens; use the React app or Swagger.

## More templates (template harvest)

A built-in job downloads new meme templates into `meme.harvest.storage-dir` (`E:/photos/meme-storage`):

| Source | When | What |
|---|---|---|
| Imgflip `get_memes` | daily 04:15 | the ~100 templates captioned most in the last 30 days — new trending formats show up here |
| memegen.link `/templates/` | Mondays 04:45 | its full template list (a few hundred), with blank images |

The scheduled runs only happen while the backend is running at those times. To run it now:

- **Easiest:** set `meme.harvest.run-on-startup: true` in `config/application.yml` and restart. It runs in the
  background (the first memegen run takes a few minutes); the log shows a "Harvest imgflip: … added" line per source. Set it back
  to `false` afterwards, or every restart downloads again.
- **Over HTTP** (ADMIN only): sign in as admin and use Swagger ("Template harvest" → `POST /api/templates/harvest`), or
  with an ADMIN key:

```bash
curl -X POST -H "X-API-Key: <admin key>" "http://localhost:8081/api/templates/harvest?source=all"   # or imgflip / memegen
curl -H "X-API-Key: <admin key>" http://localhost:8081/api/templates/harvest                        # running? last report
```

**Duplicates.** Each candidate is checked against everything already known — your `meme.templates-dir`
folder included — cheapest check first: the source id (not downloaded again), the exact bytes (SHA-256), then the
picture itself (two 64-bit dHashes + aspect ratio, `meme.harvest.max-hash-distance`). Files in your folder named
`<imgflip id>_<name>` count as that Imgflip template, so they are never downloaded again. A duplicate is stored as
an *alias* of the template it matched; when it is a clearly sharper copy of a harvested image, it replaces it.

**Folders.**

```
E:/photos/meme-storage/
  templates/       ready to use: the app picks from here too (meme.harvest.use-as-templates)
  needs-layout/    3+ text boxes and no entry in template-layouts.yml yet
  catalog.json     every template seen: source, ids, fingerprints, status, aliases, last Imgflip rank
  imgflip-ranks.jsonl   one line per daily run: that day's top-100 order (trend history)
```

- **Drop a template for good:** delete its file from `templates/`. The next run marks it `REMOVED`, and neither it
  nor a look-alike from another source comes back.
- **Multi-box templates:** they wait in `needs-layout/`, because the default top/bottom layout would put 4 captions
  in 2 boxes. Add the template to `template-layouts.yml` under its name; the next run moves it to `templates/`.
  (Or move the file yourself if the top/bottom layout is fine.)
- On a name clash, your own `meme.templates-dir` file wins over a harvested one.

**Licensing.** Meme templates are mostly film/TV stills and stock photos; "free to download" is not a license.
Every catalog entry records its source and `license: unknown`, so templates can be filtered or removed later.
Check this before a public launch.

**Prompt size.** With `AI_PICK`, every template name goes into the prompt. A few hundred templates is several
thousand extra tokens per request; if the model's rate limit becomes a problem, set
`meme.harvest.use-as-templates: false` until templates are shortlisted before the AI pick.

## AI models and failover

memecreator asks aiproxy for two *virtual models*: `auto/caption` (captions, `ai-gateway.model`) and `auto/vision`
(photo memes, `ai-gateway.vision-model`). aiproxy keeps an ordered list of real models behind each one and moves to
the next when one is rate-limited or down, inside the same call, so memecreator never notices. Which real model
answered is logged at DEBUG (`logging.level.com.memecreator.ai: DEBUG`). When every model behind a virtual model
fails, people see "The server isn't responding right now" (see "Daily prompts, Premium and AI errors").

To pin one real model instead (no failover), set e.g. `AI_GATEWAY_MODEL=groq/openai/gpt-oss-120b`.


### Prompt size: the template shortlist

When the AI picks the templates (`POST /api/web/memes` and `POST /api/memes` without templates), the prompt lists a
**shortlist** of about 15 templates (`TemplateShortlist`), not the whole pool. Listing every template came to ~9,500
input tokens once the harvest added hundreds of templates, and Groq's free tier allows 8,000 tokens per minute for
input + `max_tokens`. The shortlist is picked fresh for every request:

1. **Keyword bonus** (`meme.ai.keyword-slots`, 3): templates whose name, or a rare word in their box hints, shares a
   word with the topic ("code" → "An Older Code Sir, But It Checks Out").
2. **Well known** (`meme.ai.popular-slots`, 6): templates in Imgflip's latest top 100 (`imgflip-ranks.jsonl` in
   `meme.harvest.storage-dir`), random but weighted towards the top. No harvest yet = no ranks, this step is skipped.
3. **Variety**: the rest at random, taking one-box, two-box and three-or-more-box formats in turn.

Templates without their own text boxes in `template-layouts.yml` ("top text / bottom text") are only listed when
their name matches the topic, and templates with identical box hints count once. A template's `usage` text is cut to
`meme.ai.max-usage-chars` (160). A typical request is now ~600-900 input tokens; `CaptionPromptBudgetTest` fails if
one goes over 5,000. To see the estimate of every AI request:

```yaml
logging.level.com.memecreator.ai: DEBUG   # "AI request: model=auto/caption, estimated input tokens=..., max_tokens=1536"
```

Writing `usage` lines for your most-used templates makes both the shortlist and the captions better.

## Configuration (`src/main/resources/application.yml`)

| Property | Default |
|---|---|
| `meme.templates-dir` | `E:/photos/meme` |
| `meme.output-dir` | `E:/Work/memecreator/output` |
| `meme.count` | `4` |
| `meme.default-language` | `English` (used when a request has no `language`) |
| `meme.private-dir` / `meme.face-retention` | `E:/Work/memecreator/private` / `90d` (face photos) |
| `meme.layouts-file` | `classpath:template-layouts.yml` (or `file:E:/.../layouts.yml`) |
| `meme.fonts` | `Impact, Anton, Arial Black, Arial` (first installed wins; non-Latin text such as Persian falls back to a font that has the glyphs) |
| `meme.web.*` | `memes-per-set: 6`, `max-sets-per-hour: 20`, `free-prompts-per-day: 10`, `premium-prompts-per-day: 0` (0 = no limit), `open-dev-apis: false` — see "Web app" |
| `meme.security.*` | `admin-emails`, `api-keys-file` — see "Security" |
| `meme.harvest.*` | `storage-dir: E:/photos/meme-storage`, crons, duplicate threshold — see "More templates" |
| `ai-gateway.*` | see file; `vision-model`, `vision-max-tokens` are for photo memes, `image-format` for photo memes and AI face replacement |

Template files are expected as `<id>_<Name>.jpg|png`; the name after the underscore is what
the AI sees. Drop new images into the folder at any time — it's re-scanned automatically.
