# Pesu — spoken Tamil tutor for Android

A voice conversation tutor for colloquial Tamil. Tap the mic once; Pesu replies out loud and listens
again. Words you don't know are explained, tracked, and brought back in later chats (5-box spaced
repetition) and in the Words tab flashcards.

## Get the app
Every push to `main` builds a signed APK automatically (GitHub Actions). Open the latest
**Release** on your phone and tap `Pesu.apk`. Updates install over the old app and keep your words.

## Where the AI runs
- **Phone (default):** Google's Gemma 4 E2B via LiteRT-LM. Free, offline, no daily limit.
  One-time 2.6 GB download from Hugging Face (`litert-community/gemma-4-E2B-it-litert-lm`). Best on 6 GB+ RAM.
- **Cloud:** any OpenAI-compatible API — Google AI Studio (Gemini/Gemma, free key), Groq, OpenRouter, or your own server.
  When a free cloud limit runs out, Pesu switches to the phone model for an hour.

## Code map
| Part | Where |
|---|---|
| UI, tutor prompt (`RULES`), spaced repetition, provider logic | `app/src/main/assets/index.html` |
| Native shell: mic (SpeechRecognizer), Tamil voice (TextToSpeech), storage, HTTP streaming | `app/src/main/java/app/pesu/tamil/MainActivity.java` |
| On-phone model: download, GPU/CPU engine, streaming chat | `app/src/main/java/app/pesu/tamil/LocalLlm.kt` |
| Build | `app/build.gradle.kts`, `.github/workflows/build.yml` |

The page talks to Java/Kotlin through `window.Native` and `window.Local`; native code answers through
`window.NativeCb` (`onListen`, `onSpeak`, `onHttpChunk`, `onHttpEnd`, `onLocal`, …).

## Build locally
JDK 17 + Android SDK, then `gradle :app:assembleRelease` (Gradle 8.14). Bump `versionCode` in
`app/build.gradle.kts` for each release. **Keep `pesu-release.keystore`**: Android only installs updates
signed with the same key.

## CI result branch
The workflow force-pushes `build.log`, `STATUS` and the APK to the `ci` branch after each run, so tools
without GitHub API access can read build results with plain `git fetch`.
