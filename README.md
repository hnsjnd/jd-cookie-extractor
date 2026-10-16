# 京东 Cookie 提取器

多账号安卓工具：分区管理 **京豆（京东）** / **美团**（微软占位），用独立 WebView 或粘贴方式提取 Cookie/token，并推送到青龙面板。

> Cookie / token 默认只保存在本机。请勿泄露给他人。

## 功能（v1.8.0）

- **分区 UI**：顶部 Tab「京豆 / 美团 / 微软」
  - **京豆**：原有京东多账号、京豆统计、养号模式、失效提醒、推送 `JD_COOKIE`
  - **美团**：多账号列表；WebView 登录提取 `token` 或手动粘贴；推送青龙环境变量 **`meituanCookie`**（值为纯 token，如 `Ag…`，一账号一变量）
  - **微软**：占位「即将支持」
- Cookie 失效提醒、养号模式、京豆卡片、一键更新等（见往期版本）

## 使用

1. 安装 Release APK
2. 菜单 → 青龙配置：填写 OpenAPI（client_id / client_secret）
3. **京豆** Tab：添加账号并登录；点「更新」刷新 CK / 京豆 / 推送状态
4. **美团** Tab：添加 → WebView 登录或粘贴 token；自动/手动推送到 `meituanCookie`

## 构建

```bash
./gradlew assembleRelease
```

签名密钥放在仓库外，切勿提交。

## 注意

- 仅供个人使用
- minSdk 24 / targetSdk 34
- 美团 WebView 登录受站点风控影响，失败时请用粘贴 token
