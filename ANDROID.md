# DS-API Android 客户端

APK 使用 DS-API 网页前端，并通过本地 Android `WebView` 加载打包资源。应用不需要本地服务器，API Key、会话和附件均保存在设备本地。

账号登录直接在 App 内打开 DeepSeek 开放平台官方登录页。页面由内置 GeckoView 渲染，不依赖系统 WebView；账号密码、验证码和第三方登录只提交给官方页面。登录完成后，内置同源桥接读取开放平台登录态，App 自动调用普通 API Key 接口、完成 `/models` 校验并返回聊天界面。平台登录 Token 使用 Android Keystore 加密保存，仅用于后续读取账户余额。

## 一键构建

```powershell
pnpm install
pnpm android
```

安装包输出：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

由于 APK 内置 GeckoView 内核，体积约 360–390 MB。当前包含 `arm64-v8a` 与 `x86_64`，分别覆盖主流实体 Android 手机和 Android Studio 模拟器。

### 覆盖升级与数据

同一签名、同一 `applicationId` 的新版本应通过 Android 覆盖安装升级，`adb install -r` 和系统安装器直接覆盖安装都属于这种方式。此路径不会删除 WebView 中的 API Key、IndexedDB 会话或附件。

应用已启用 Android Auto Backup，并把 `app_webview`、数据库、SharedPreferences 和普通文件纳入云备份及设备迁移。先卸载旧版本再安装新版本会删除 Android 应用沙箱；卸载前应先使用设置中的历史导出功能。生产发布必须持续使用同一份正式签名证书，签名变化会阻止覆盖安装。

安装到已连接的设备：

```powershell
C:\Users\work\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r android\app\build\outputs\apk\debug\app-debug.apk
```

## Android 壳提供的能力

- 同源安全页面 `https://appassets.androidplatform.net`，允许直接请求 DeepSeek API 与使用 `getUserMedia`。
- 文件选择器、多选附件、麦克风权限与 WebView 权限回调。
- Blob 文件分块保存到系统 `Downloads/DeepSeek`，用于历史会话导出和附件下载。
- 外部引用链接交给系统浏览器打开。
- 系统栏颜色随应用明暗主题同步。
- 返回键优先返回 WebView 历史。
- GeckoView 承载 DeepSeek 开放平台官方登录页，规避旧版 System WebView 白屏。
- 官方页面登录态读取、自动创建普通 API Key 与 `/models` 校验。
- App 内打开开放平台官方「用量与余额」和「充值」页面，支付仍由开放平台及支付渠道处理。
- 应用启动、回到前台及每 5 分钟通过 `/models` 检查 API Key；Key 被删除后设置页会切换为「重新获取 API Key」。
- 设置页直接请求开放平台账户摘要并显示充值余额与赠金余额，无需打开充值页。

## 目录

```text
android/
  app/src/main/java/com/deepseek/chat/local/MainActivity.java
  app/src/main/AndroidManifest.xml
  app/src/main/res/
  build.gradle
  settings.gradle
```

`android/app/build.gradle` 会在构建前把根目录 `dist/` 同步到 APK 的 `assets/web/`，所以修改前端后重新运行 `pnpm android` 即可。

## 网页版账号登录

网页版需要本地认证服务，因此使用：

```powershell
pnpm build
pnpm web
```

打开 `http://127.0.0.1:8090`。点击设置中的登录按钮后，本地服务会启动一个独立的 Chrome 或 Edge 窗口。请在官方开放平台页面完成登录；本地服务随后读取该窗口的同源登录态，自动创建并验证普通 API Key，然后关闭登录窗口。

网页版的「用量与余额」和「充值」也使用同一份独立 Chrome/Edge 配置打开，确保与自动登录流程共享开放平台登录态。

本地服务使用浏览器远程调试端口读取登录态，端口只绑定 `127.0.0.1`，并使用独立的浏览器资料目录。默认自动查找 Chrome/Edge；其他 Chromium 浏览器可通过 `CHROME_PATH` 指定。`pnpm dev` 只启动前端开发服务器，不包含账号登录服务。

可用 mock 平台验证从开放平台登录态到 API Key 创建的完整协议链路：

```powershell
pnpm test:auth
```
