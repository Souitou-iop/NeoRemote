# NeoRemote Android 签名预设规范

## 目标

NeoRemote Android 端统一采用“构建未签名 APK，再按环境签名”的流程：

- 本地调试包可以使用 Android debug keystore 快速签名。
- 正式分发包必须使用 NeoRemote release keystore 签名。
- GitHub Actions 只通过 GitHub Secrets 读取签名材料，仓库内禁止提交 keystore、密码、签名后的派生产物。

## 产物类型

| 类型 | 文件名 | 用途 | 签名方式 |
| --- | --- | --- | --- |
| 未签名 release APK | `NeoRemote-android-release-unsigned.apk` | CI 中间产物、人工复签 | 不签名 |
| 本地调试签名 APK | `NeoRemote-android-release-debug-signed.apk` | 临时安装测试 | Android debug keystore |
| 正式签名 APK | `NeoRemote-android-release-signed.apk` | 对外分发/验收 | NeoRemote release keystore |

首版继续输出 APK，不切 AAB。等需要应用商店分发时，再新增 `bundleRelease` 和 `.aab` 签名流程。

## 本地签名预设

本地快速验收可以使用系统 debug keystore：

```bash
cd /Users/ebato/Documents/Projects/NeoRemote

GRADLE_USER_HOME="$PWD/.gradle-user-home" ./Android/gradlew \
  --project-dir Android \
  assembleRelease

cp Android/app/build/outputs/apk/release/app-release-unsigned.apk \
  release/NeoRemote-android-release-unsigned.apk

apksigner sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out release/NeoRemote-android-release-debug-signed.apk \
  release/NeoRemote-android-release-unsigned.apk

apksigner verify --verbose --print-certs \
  release/NeoRemote-android-release-debug-signed.apk
```

本地正式签名使用 release keystore：

```bash
cd /Users/ebato/Documents/Projects/NeoRemote

apksigner sign \
  --ks "$NEOREMOTE_ANDROID_KEYSTORE_PATH" \
  --ks-key-alias "$NEOREMOTE_ANDROID_KEY_ALIAS" \
  --ks-pass "pass:$NEOREMOTE_ANDROID_KEYSTORE_PASSWORD" \
  --key-pass "pass:$NEOREMOTE_ANDROID_KEY_PASSWORD" \
  --out release/NeoRemote-android-release-signed.apk \
  release/NeoRemote-android-release-unsigned.apk

apksigner verify --verbose --print-certs \
  release/NeoRemote-android-release-signed.apk
```

## Release keystore 创建规范

只需要创建一次，创建后必须离线保存并备份：

```bash
keytool -genkeypair \
  -v \
  -keystore NeoRemote-release.jks \
  -storetype JKS \
  -alias neoremote-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=NeoRemote, OU=NeoRemote, O=NeoRemote, L=Unknown, ST=Unknown, C=US"
```

推荐默认值：

- Keystore 文件名：`NeoRemote-release.jks`
- Key alias：`neoremote-release`
- Store type：`JKS`
- Key algorithm：`RSA`
- Key size：`4096`
- Validity：`10000` 天

安全要求：

- 不要把 `NeoRemote-release.jks` 放进仓库。
- 不要把 keystore 密码写进 Gradle、shell 脚本、Markdown 示例以外的真实配置。
- 更换 release keystore 等同于更换应用签名身份，必须作为发布级风险处理。

## GitHub Secrets 规范

GitHub Actions 正式签名需要配置以下 Secrets：

| Secret | 含义 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `NeoRemote-release.jks` 的 base64 内容 |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | key alias，默认 `neoremote-release` |
| `ANDROID_RELEASE_KEY_PASSWORD` | key 密码 |

本地生成 base64：

```bash
base64 -i NeoRemote-release.jks | pbcopy
```

GitHub Actions 中还原：

```bash
echo "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/NeoRemote-release.jks"
```

## GitHub Actions 签名步骤模板

在 `assembleRelease` 之后添加：

```yaml
- name: Decode Android release keystore
  env:
    ANDROID_RELEASE_KEYSTORE_BASE64: ${{ secrets.ANDROID_RELEASE_KEYSTORE_BASE64 }}
  run: |
    set -euo pipefail
    echo "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/NeoRemote-release.jks"

- name: Sign release APK
  env:
    ANDROID_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD }}
    ANDROID_RELEASE_KEY_ALIAS: ${{ secrets.ANDROID_RELEASE_KEY_ALIAS }}
    ANDROID_RELEASE_KEY_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEY_PASSWORD }}
  run: |
    set -euo pipefail
    mkdir -p artifacts
    "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" sign \
      --ks "$RUNNER_TEMP/NeoRemote-release.jks" \
      --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" \
      --ks-pass "pass:$ANDROID_RELEASE_KEYSTORE_PASSWORD" \
      --key-pass "pass:$ANDROID_RELEASE_KEY_PASSWORD" \
      --out artifacts/NeoRemote-android-release-signed.apk \
      Android/app/build/outputs/apk/release/app-release-unsigned.apk
    "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" verify \
      --verbose \
      --print-certs \
      artifacts/NeoRemote-android-release-signed.apk
```

如果 Secrets 缺失，正式签名 job 应该失败，不要自动退回 debug keystore。debug 签名只能用于本地或明确命名的 debug artifact。

## Gradle 配置边界

当前推荐继续保持 `Android/app/build.gradle.kts` 不内置签名信息：

- `release` buildType 不写 `signingConfig`。
- CI 用 `apksigner` 在 Gradle 构建后签名。
- 这样可以避免 Gradle 配置读取 Secrets、避免误把签名材料写入仓库。

如果后续要改成 Gradle 内签名，必须满足：

- 只从环境变量读取密码和 keystore 路径。
- `signingConfigs` 不包含任何真实密码。
- CI 和本地命令都必须保留 `apksigner verify` 校验。

## 验收标准

每次 Android release 产物必须满足：

- `./Android/gradlew --project-dir Android assembleRelease` 成功。
- `apksigner verify --verbose --print-certs <apk>` 成功。
- `unzip -t <apk>` 无错误。
- 文件名能区分 unsigned、debug-signed、release-signed。
- `git status` 中不能出现已 stage 的 keystore、`.idsig`、`release/` 产物。

## 禁止事项

- 禁止提交 `.jks`、`.keystore`、`.idsig`。
- 禁止提交真实签名密码。
- 禁止把 debug 签名包命名成正式签名包。
- 禁止跳过 `apksigner verify`。
- 禁止把 `release/` 目录作为源码提交。
