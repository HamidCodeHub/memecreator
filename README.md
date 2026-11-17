# Meme Creator

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
JSON mode on, and `max-tokens: 2000` because reasoning tokens share the output budget.
See `src/main/resources/application-groq.yml`. In IntelliJ set *Active profiles* to `groq`.

## AI gateway contract

The app sends an OpenAI-compatible chat completion:

```
POST {ai-gateway.base-url}{ai-gateway.chat-path}      (default /v1/chat/completions)
Authorization: Bearer {ai-gateway.api-key}             (only if a key is set)
{"model": "...", "temperature": 0.9, "max_tokens": 800,
 "messages": [{"role":"system","content":"..."}, {"role":"user","content":"..."}]}
```

It reads the reply text from `choices[0].message.content`, and also accepts Anthropic-style
`content[].text` or a plain `output_text` / `text` field. If your gateway uses another path,
change `ai-gateway.chat-path` in `application.yml`.

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
- To add or tune one: edit the file, rebuild (Ctrl+F9 in IntelliJ copies it to `target/classes`; the app
  re-reads it without a restart), and open `/api/templates/preview?name=<template>` to see the boxes.

## Configuration (`src/main/resources/application.yml`)

| Property | Default |
|---|---|
| `meme.templates-dir` | `E:/photos/meme` |
| `meme.output-dir` | `E:/Work/memecreator/output` |
| `meme.count` | `4` |
| `meme.default-language` | `English` (used when a request has no `language`) |
| `meme.layouts-file` | `classpath:template-layouts.yml` (or `file:E:/.../layouts.yml`) |
| `meme.fonts` | `Impact, Anton, Arial Black, Arial` (first installed wins; non-Latin text such as Persian falls back to a font that has the glyphs) |
| `ai-gateway.*` | see file |

Template files are expected as `<id>_<Name>.jpg|png`; the name after the underscore is what
the AI sees. Drop new images into the folder at any time — it's re-scanned automatically.
