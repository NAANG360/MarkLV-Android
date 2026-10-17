# Jarvis Android

A native Android personal assistant inspired by the feature set of Mark-LV. This repository is an Android adaptation, not an exact reproduction of the desktop application. The original project targets Windows, macOS and Linux and relies on desktop-only APIs; Jarvis replaces those with Android-native APIs where possible.

## Current implementation

- Kotlin + Jetpack Compose interface with an animated Jarvis HUD orb
- Bundled FreeLLMAPI server build pipeline, using an embedded Node.js mobile runtime
- FreeLLMAPI provider dashboard hosted locally inside the APK's WebView
- OpenAI-compatible chat/tool calling with the FreeLLMAPI auto router
- Optional HTTPS fallback providers if the local router or a provider is unavailable
- Android speech recognition and text-to-speech
- Encrypted provider credentials using Android Keystore
- Local conversation history, searchable long-term facts, and image/text-file attachments (images are sent as multimodal content and not retained as image bytes in history)
- User-visible memory clearing
- Android app launching, URL/deep-link opening, web/YouTube/weather search, maps, camera, WhatsApp draft, clipboard read/write, contact search, dialer handoff, calendar-event form and Android share sheet
- Optional Accessibility Service for reading visible text, tapping labelled controls, entering text in the focused field, scrolling and Back navigation
- Device time/battery status, media volume control, recent notification summaries (opt-in), and shortcuts to supported Android settings pages
- Reminders scheduled through Android alarms, saved for reboot recovery, with notification permission controls
- Undo for the last supported media-volume change or scheduled reminder
- GitHub Actions build that downloads the Node runtime, builds FreeLLMAPI, embeds its dashboard/server assets, compiles the APK, and uploads a debug APK artifact

## Standalone runtime

The intended build starts FreeLLMAPI inside the app process. No Termux, shell, Docker, manually started server or separately installed backend is required. The build workflow pins the FreeLLMAPI source revision and downloads a Node.js 24 mobile runtime from the community-maintained fogtape/nodejs-mobile release line. That mobile runtime is a prerelease packaging line, so the embedded server must be validated on-device before treating a build as production-ready.

FreeLLMAPI remains responsible for its provider catalog, encrypted provider-key database, model routing, rate tracking and failover. Add provider keys from Jarvis Settings → Manage FreeLLMAPI providers. Free tiers still require provider credentials where the provider requires them, and their quotas and terms continue to apply.

The optional fallback-route format is one route per line:

    name|https://provider.example/v1|model-id|api-key

Only HTTPS direct-provider URLs are accepted. The bundled local FreeLLMAPI endpoint is loopback-only.

## Android permissions and limitations

- The Accessibility Service is opt-in. Android and individual apps can restrict protected screens, secure UI, or controls without readable labels.
- Voice input currently uses Android's speech-recognition intent; it is not yet the same bidirectional low-latency Gemini Live audio session used by the desktop app.
- The animated orb is a native HUD treatment, not yet the desktop app's full 3D human head, viseme lip-sync and facial acting renderer.
- Direct screen capture, webcam vision, broad document parsing, proactive background monitoring, wake-word detection, full action journaling/undo, audio-device selection, and a general plugin system still need Android-native implementations. Text/JSON and image attachments are supported, but are not a substitute for continuous screen or camera awareness.
- Desktop-only window/taskbar controls have no direct Android equivalent; app launching and Accessibility actions are the Android replacements.
- Android may suspend or terminate background processes. The embedded server starts again with the app process; this is not a guarantee of an always-on background daemon.

## Build and APK

Use Actions → Build Jarvis Android APK in GitHub. The workflow builds an arm64 APK and uploads it as the Jarvis-Android-debug artifact. A source commit or an empty CI-status response is not proof of a successful build; check that workflow run and its artifact before installing.

The Node runtime and FreeLLMAPI assets are staged by CI and are not committed as large generated binaries. A plain local Android Studio build without those staged assets will not include the embedded router.

## Privacy

Chat prompts go to the configured local FreeLLMAPI router, which then sends requests to whichever provider keys you configure. Review those providers' privacy and retention terms before sending sensitive information. API credentials are encrypted at rest with Android Keystore; chat history and memory are kept in app-private preferences. The embedded dashboard is bound to loopback and is not intended to be exposed to the LAN or Internet.

## Attribution

- FreeLLMAPI: MIT-licensed upstream project, bundled at build time from a pinned source revision.
- Node.js mobile runtime: Node.js and mobile runtime components retain their respective upstream/community licenses; review their notices before redistribution.
- Mark-LV: feature inspiration only. No desktop source code or upstream assets are bundled in this repository.

This is a work in progress. Feature parity is being approached through Android-native equivalents; exact desktop 1:1 parity is not technically possible for desktop-only features.


<!-- Temporary CI validation marker; do not merge. -->
