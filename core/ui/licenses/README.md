# Bundled font licenses

`core/ui/src/main/res/font/` bundles two typefaces as Android font resources:

- **Lora** (`lora.ttf`) — Google Fonts, SIL Open Font License 1.1. Used for
  editorial display/headline/title styles (`Type.kt`).
- **OpenDyslexic** (`opendyslexic_regular.ttf`, `opendyslexic_bold.ttf`) —
  by Abbie Gonzalez ([opendyslexic.org](https://opendyslexic.org)), SIL Open
  Font License 1.1. Backs the "OpenDyslexic" reading-font option (#423) for
  Compose/Markdown-rendered text. Full license text: `OpenDyslexic-OFL.txt`
  in this directory.

Both fonts are OFL-licensed, which explicitly permits bundling/embedding in
a distributed app (including a donation-only, no-Pro-tier app like this one —
see `project_license_activation_placeholder`) as long as the font itself
isn't sold standalone and any Reserved Font Name isn't reused by a modified
version. Neither restriction applies here — the fonts are used unmodified.

Note the EPUB reading path (Readium's WebView-rendered navigator) does *not*
use the bundled `opendyslexic_*.ttf` files above — Readium's own
`readium-navigator` artifact already embeds OpenDyslexic internally
(`org.readium.r2.navigator.preferences.FontFamily.OPEN_DYSLEXIC`, see
`readium/kotlin-toolkit`'s own "EPUB font families" guide), so EPUB rendering
reuses that copy instead of shipping a second one. The bundled files here are
only for the app's own Compose-rendered surfaces (Markdown, in-app text).
