# Mark LV Android

Native Android companion inspired by [Mark-LV](https://github.com/FatihMakes/Mark-LV). This is an Android-first implementation, not a claim of exact feature parity with the desktop app.

## Included
- Kotlin + Jetpack Compose chat UI
- Gemini REST API chat (user supplies their own API key)
- Android speech recognition and Text-to-Speech
- Local conversation history
- GitHub Actions debug APK build

Desktop OS automation, desktop screen capture, and the original desktop holographic renderer need Android-specific replacements and are not included yet. Gemini usage is subject to Google's quotas and terms; it is not unlimited.

## Build
Open in Android Studio with JDK 17. Or push to GitHub and run **Actions → Build Android APK**. The workflow uploads `app-debug.apk` as an artifact.

Add your Gemini API key in the app's Settings. Do not commit API keys.

## Attribution
Independent Android implementation inspired by Mark-LV's public feature set; not affiliated with FatihMakes. No upstream source or assets are bundled. Check upstream licensing before redistributing copied code/assets.