# 京东 Cookie 提取器

简洁安卓工具：用 WebView 登录**自己的**京东账号，一键提取 `pt_key` / `pt_pin`。

> Cookie 仅保存在本机，不会上传到任何服务器。请勿泄露给他人。

## 功能

- Material 3 中文界面，支持深色模式
- WebView 打开京东手机版 / 登录页
- 实时显示登录状态（已登录账号 / 未登录）
- 一键提取，格式化为 `pt_key=...;pt_pin=...;`
- 分别复制完整 Cookie / `pt_key` / `pt_pin`
- 系统分享、二维码展示（可选）
- 清除 Cookie 并重新登录 / 切换账号
- 本地历史记录（SharedPreferences，最多 20 条）

## 使用

1. 安装 Release 中的 APK，或用 Android Studio 打开本项目运行
2. 在 App 内用手机号验证码登录京东（建议）
3. 点击「提取 Cookie」
4. 复制完整 Cookie，贴到青龙等工具的 `JD_COOKIE` 环境变量

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

签名密钥存放在仓库外的 `/workspace/keys`（或本机任意路径），通过 `keystore.properties` 配置，切勿提交到 Git。

## 注意

- 仅供个人使用，Cookie 请勿泄露
- 建议用手机号验证码登录，有效期更长
- minSdk 24，targetSdk 34
