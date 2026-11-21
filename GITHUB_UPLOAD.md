# GitHub Upload Checklist

## Upload

- Root configuration: `.gitignore`, `.oxfmtrc.json`, `.oxlintrc.json`, `package.json`, `pnpm-lock.yaml`, `tsconfig.json`, `rspack.config.ts`
- Documentation: `README.md`, `README.en.md`, `README.zh-CN.md`, `ARCHITECTURE.md`, `ANDROID.md`, `NOTICE.md`, `LICENSE`, `LICENSES/`
- Workflow: `.github/workflows/pages.yml`
- Release workflow: `.github/workflows/release.yml`
- Web source: `src/`
- Build and helper scripts: `scripts/`
- Android source and Gradle wrapper: `android/` except ignored build/local/signing files

## Never commit

- `node_modules/`, `.pnpm-store/`
- `dist/`, `build/`, `android/**/build/`
- `release/`, `artifacts/`, `tmp/`, browser profiles
- `android/local.properties`
- `.env`, credentials, tokens
- `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties`
- APK/AAB build outputs

These rules are enforced by `.gitignore`.

## Publish binaries

Use GitHub Releases rather than normal Git history:

1. Build `DS-API-Android-<version>.apk`.
2. Run `pnpm build && pnpm package:html`.
3. Upload the APK, HTML ZIP, and SHA-256 files to a GitHub Release.

## Release checks

```powershell
pnpm type
pnpm lint
pnpm test:auth
pnpm build
pnpm android
pnpm package:html
git diff --check
```

Android upgrades must use the same `applicationId` and signing certificate as the previous release.
