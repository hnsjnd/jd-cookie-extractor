# 京东 Cookie 提取器

多账号安卓工具：用 **独立 WebView 会话** 登录自己的京东账号，自动提取 `pt_key` / `pt_pin`，并支持本地 CK 检测与可选青龙推送。

> Cookie 默认只保存在本机。请勿泄露给他人。

## 功能（v1.4.0）

- **账号列表主界面**：昵称 / pt_pin、有效/失效状态、最近更新时间
- **添加账号**：先清空 WebView Cookie，再打开登录页，账号互不串号
- **登录后自动提取**：无需手动点「提取」；步骤条：等待登录 → 检测中 → 提取中 → 已保存
- 重新登录、重命名、删除、复制单个 / 全部 Cookie（`&` 或换行）
- **任务进度**：批量请求京东 `GetJDUserInfoUnion`（与 [ql-jd-self](https://github.com/hnsjnd/ql-jd-self) 相同接口）检测 CK，显示总进度与每账号结果
- **主流程：登录 → 自动提取 → 自动推送到青龙**（OpenAPI 更新/新建 JD_COOKIE）
- **应用内青龙面板**（菜单入口）：主屏「青龙面板」按钮打开配置的面板地址（默认 `https://ql.xiaowo1.xyz`），独立保留登录 Cookie；任务页快捷入口 `/crontab`、`/log`
- **可选青龙 OpenAPI**：client_id/secret 用于推送/更新 `JD_COOKIE` 与拉取定时任务列表

## 使用

1. 安装 Release APK
2. 点右下角 **+** 添加账号并完成登录（建议手机号验证码）
3. 回到列表可复制 Cookie；在「任务进度」里一键检测全部
4. （可选）菜单 → 青龙面板配置 → 推送环境变量

## 构建

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

签名密钥放在仓库外，切勿提交。

## 注意

- 仅供个人使用
- minSdk 24 / targetSdk 34
