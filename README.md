# DS-API

[English summary](README.en.md) | 简体中文

DS-API 是一个基于 DeepSeek API 的网页与 Android 聊天客户端。它复用并扩展了 DeepSeek 官方仓库
[`deepseek-ai/chat-demo`](https://github.com/deepseek-ai/chat-demo) 的网页前端、交互元素和部分功能实现，
并在此基础上增加了开放平台账号登录、自动创建 API Key、API Key 状态检测、账户余额显示、App 内充值和
Android 客户端等功能。

> **非官方声明**
>
> DS-API 是独立二次开发项目，不是 DeepSeek 官方产品，也不代表 DeepSeek 对本站或本 App 的认可、背书或合作。
>
> DeepSeek 名称、Logo、图标、界面素材、服务标识及其他商标或品牌元素，均归 DeepSeek 及其关联方所有。
> 本项目中的 `DS-API` 仅作为独立客户端的项目名称，不是 DeepSeek 商标。官方登录页、开放平台页面和支付页面上
> 由 DeepSeek 提供的品牌元素保持原样。

## 功能

- 多会话、自动标题、重命名、删除、消息分支、重新生成和继续生成。
- 流式回复、深度思考、智能搜索、链接阅读和来源引用。
- 文件、图片、粘贴和拖拽上传，语音输入与回复朗读。
- Markdown、代码高亮、KaTeX 公式和 Mermaid 图表。
- 中英文界面、浅色/深色主题、移动端布局和历史对话导入导出。
- 在 App 内登录开放平台并自动创建 API Key。
- 启动、前台恢复、定时请求和鉴权失败时检测 API Key 状态。
- 直接读取开放平台账户余额，显示充值余额与赠金余额。
- 在 App 内打开官方用量页、余额页和充值页；支付仍由 DeepSeek 与对应支付渠道处理。

## 项目组成

| 部分 | 说明 |
| --- | --- |
| `src/` | React 网页前端，来源于官方 `chat-demo` 并经过二次开发 |
| `android/` | 使用 GeckoView 与本地 WebView 的 Android 客户端 |
| `scripts/build.mjs` | 构建纯静态网页 |
| `scripts/package-html.mjs` | 生成可独立分发的 HTML 目录和 ZIP |
| `scripts/web-auth-server.mjs` | 本地网页登录助手和余额接口 |
| `scripts/test-web-auth.mjs` | 开放平台登录、API Key 和余额协议 mock 测试 |
| `.github/workflows/pages.yml` | GitHub Pages 自动构建与发布 |
| `.github/workflows/release.yml` | CI 构建 Android APK、HTML ZIP 并发布到 GitHub Release |

更完整的模块边界、数据流和认证流程见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 网页版

需要 Node.js 22.12+ 和 pnpm：

```powershell
pnpm install
pnpm dev
```

开发地址为 `http://localhost:8090`。

构建纯静态网页：

```powershell
pnpm build
```

产物位于 `dist/`。它可以部署到任意静态网站服务器；直接打开 `dist/index.html` 时，浏览器需要允许
`file://` 页面的本地存储，API 服务也需要允许对应的跨域请求。

### 网页版账号登录

普通静态网页受同源策略限制，不能直接读取 `platform.deepseek.com` 的登录态。因此本地网页版使用：

```powershell
pnpm build
pnpm web
```

点击账号登录后，本地助手会启动一个独立的 Chrome/Edge 窗口。用户在 DeepSeek 官方页面完成登录，
本地助手再从该浏览器配置读取登录态并创建 API Key。远程调试端口仅绑定 `127.0.0.1`，浏览器登录态保存在
独立资料目录中。

可以通过 `CHROME_PATH` 指定其他 Chromium 浏览器。

## Android 客户端

构建：

```powershell
pnpm android
```

APK 输出：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

当前包含 `arm64-v8a` 和 `x86_64`。由于内置 GeckoView，APK 体积约 360–390 MB。

Android 使用 GeckoView 打开官方登录、用量和充值页面，聊天主界面仍由本地 WebView 加载。平台登录 Token
使用 Android Keystore 加密保存，API Key 和聊天数据保存在本地 WebView 存储中。

详见 [ANDROID.md](ANDROID.md)。

## 纯 HTML 包

先构建网页，再生成独立目录、ZIP 和校验文件：

```powershell
pnpm build
pnpm package:html
```

输出：

```text
release/DS-API-Web-<version>/
release/DS-API-Web-<version>.zip
release/DS-API-Web-<version>.zip.sha256
```

该目录只包含运行网页所需的 HTML、CSS、JavaScript、字体和许可证文件，不包含 Node.js 依赖、源码或本地配置。

GitHub Actions 的 `Build and Publish Release` 工作流会在 Ubuntu runner 上同时构建 Android APK
和 HTML ZIP，校验 tag 与 `package.json` 版本一致后自动创建 Release 并上传全部资产。

## 数据与安全

- API Key 与普通设置保存在浏览器 localStorage。
- 会话、附件和 IndexedDB 数据按站点源隔离。
- 聊天内容、上传文件、语音和余额请求会发送到配置的 DeepSeek API/开放平台服务。
- Android 平台登录 Token 使用 Android Keystore 加密；网页版登录 Token 保存在独立浏览器配置中。
- 应用不会保存银行卡号、支付密码或验证码。
- 充值、支付、退款和账单由 DeepSeek 开放平台及其支付渠道处理。

## 升级

Android 覆盖升级必须保持相同 `applicationId` 和相同签名证书。使用系统安装器或 `adb install -r` 升级会保留
应用数据；先卸载再安装会删除 Android 应用沙箱。应用已启用 Android Auto Backup 和 WebView 数据迁移规则。

生产发布前应配置固定的正式签名证书，不应在不同版本之间重新生成签名。

## 开发检查

```powershell
pnpm type
pnpm lint
pnpm test:auth
pnpm build
pnpm android
pnpm package:html
```

## 来源、许可证与商标

- 官方上游仓库：[`deepseek-ai/chat-demo`](https://github.com/deepseek-ai/chat-demo)
- DeepSeek API 文档：[`https://api-docs.deepseek.com/`](https://api-docs.deepseek.com/)
- 上游 MIT 许可证原文：[`LICENSES/DeepSeek-chat-demo-MIT.txt`](LICENSES/DeepSeek-chat-demo-MIT.txt)
- 本项目许可证：[GNU GPL v3](LICENSE)
- 第三方组件遵循各自许可证。

完整来源和商标声明见 [NOTICE.md](NOTICE.md)。GitHub 提交范围见 [GITHUB_UPLOAD.md](GITHUB_UPLOAD.md)。
