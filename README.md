# Jarvis Android

A native Android personal assistant inspired by the idea of Mark-LV/Jarvis. This is an independent Android-first implementation, not a claim of feature parity with any desktop project.

## Current MVP

- Kotlin + Jetpack Compose chat interface branded Jarvis
- OpenAI-compatible FreeLLMAPI endpoint configuration
- Model/routing name configuration (use the value your FreeLLMAPI deployment supports; `auto` is the default suggestion)
- Optional bearer token for deployments that require authentication
- Function/tool calling for three Android actions: launch an installed app by its visible name, open a URL/deep link, and open a web search
- Android speech recognition and Text-to-Speech
- Local conversation history in app-private preferences
- GitHub Actions debug APK workflow

## FreeLLMAPI setup

This app calls `/v1/chat/completions` on a FreeLLMAPI-compatible server. It does **not** bundle a server or automatically create a public endpoint. Enter the base URL for a FreeLLMAPI instance you control or are authorized to use, plus any required API key and a supported model/routing name.

Examples:
- Base URL `https://your-host.example` → request path `/v1/chat/completions`
- Base URL ending in `/v1` → request path `/chat/completions`

Provider quotas, availability, authentication, and terms still apply. Never paste secrets into public issues or commit them to this repository.

## Android actions and permissions

Actions run through normal Android intents and package-manager APIs. Android may show a chooser, deny an operation, or lack a matching app. This MVP does not execute shell commands, use root, or control Miss Minutes. Optional cross-app interactions are available through a dedicated Accessibility Service for tapping visible text, entering text into the focused field, scrolling, and Back navigation. The user must explicitly enable this service in Android Settings; some apps and protected screens may still block interaction.

## Build

Open in Android Studio with JDK 17, or use **Actions → Build Android APK** in GitHub. The workflow uploads `app-debug.apk` as an artifact. A successful source commit is not proof that the APK built; check the latest Actions run for the actual build result.

## Privacy

Conversation history and settings are stored in app-private preferences. Messages are sent to the configured model endpoint when you chat. Review that server/provider's privacy and retention policies before using sensitive data.

## Attribution

Independent Android implementation. Not affiliated with the original Mark-LV project. No upstream source or assets are bundled.
