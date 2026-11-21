# DS-API Architecture

## Layers

| Layer | Responsibility |
| --- | --- |
| React web UI (`src/`) | Chat interface, sessions, settings, attachments, speech, Markdown |
| Chat API client (`src/services/api/`) | DeepSeek API requests, streaming, file APIs, API-key verification |
| Account status (`src/services/apiKeyStatus*.ts`) | API-key health state shared by settings and transport errors |
| Platform account (`src/services/platform*.ts`) | Usage/balance/top-up entry and Platform login integration |
| Android shell (`android/`) | WebView chat host, GeckoView official Platform pages, secure token storage |
| Web login helper (`scripts/`) | Loopback-only local server and isolated Chromium profile |
| Build tools (`scripts/build*.mjs`) | Static web build, HTML packaging, Android build |

## Web chat flow

1. React renders from `src/main.tsx`.
2. Settings expose manual API-key entry or account-based provisioning.
3. API calls go directly to `https://api.deepseek.com`.
4. A 401 response or periodic `/models` check marks the current API-key state invalid.
5. Balance requests use a separate Platform token, never the model API key.

## Local web account flow

1. `pnpm web` starts a loopback-only HTTP server.
2. `/__auth/start` launches an isolated Chromium profile.
3. The user signs in on the official Platform page.
4. The helper reads the same-origin Platform login token through a loopback debug endpoint.
5. The helper creates and verifies an API key, then returns it to the web UI.
6. `/__platform/balance` reads the official account summary.
7. Usage and top-up actions reuse the same isolated browser profile.

## Android flow

1. `MainActivity` hosts the chat frontend in a same-origin WebView.
2. Login, usage, and top-up actions open GeckoView activities.
3. A bundled content script reads the Platform login token from the official page.
4. The token is encrypted with Android Keystore for later balance requests.
5. The API key is created through the ordinary Platform API-key endpoint and verified through `/models`.
6. Activity results return to the WebView through the local `DeepSeekAndroid` JavaScript bridge.

## Data layout

| Data | Storage |
| --- | --- |
| API key and UI settings | WebView localStorage |
| Conversations, messages, attachments | IndexedDB |
| Android Platform token | Android Keystore-encrypted SharedPreferences |
| Web helper Platform token | Chromium profile localStorage |
| Release APK and HTML packages | `release/` locally, ignored by Git |

## Failure handling

- API-key invalid: settings change to “重新获取 API Key”.
- API-key network failure: last proven valid state is retained; unknown states can retry.
- Platform token missing: balance area asks for Platform sign-in.
- Platform token expired: the token is cleared and sign-in is requested again.
- Payment: only official DeepSeek payment UI is used.
