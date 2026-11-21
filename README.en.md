# DS-API

简体中文 | [English]

DS-API is an independent web and Android client for the DeepSeek API. Its web frontend and interaction model are
derived from the official [`deepseek-ai/chat-demo`](https://github.com/deepseek-ai/chat-demo) repository and extended
with account sign-in, automatic API-key provisioning, API-key health checks, Platform balance display, in-app
usage/top-up pages, and an Android shell.

## Important notice

This is not an official DeepSeek product. It is not affiliated with, endorsed by, or sponsored by DeepSeek.

The DeepSeek name, logos, icons, interface assets, service marks, and other brand elements belong to DeepSeek and its
affiliates. `DS-API` is an independent project name, not a DeepSeek trademark. Brand elements rendered by official
DeepSeek login, Platform, and payment pages are preserved as provided by DeepSeek.

## Quick start

```sh
pnpm install
pnpm dev
```

Static web build:

```sh
pnpm build
pnpm package:html
```

Android:

```sh
pnpm android
```

Local web sign-in helper:

```sh
pnpm build
pnpm web
```

See [README.md](README.md), [ARCHITECTURE.md](ARCHITECTURE.md), [ANDROID.md](ANDROID.md), and [NOTICE.md](NOTICE.md)
for details.

## License

GNU GPL v3. The upstream MIT license is retained in
[`LICENSES/DeepSeek-chat-demo-MIT.txt`](LICENSES/DeepSeek-chat-demo-MIT.txt).
