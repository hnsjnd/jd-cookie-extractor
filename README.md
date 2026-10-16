# 京东 Cookie 提取器

多账号安卓工具：分区管理 **京豆（京东）** / **美团** / **饿了么**（微软占位），用独立 WebView 或粘贴方式提取 Cookie/token，并推送到青龙面板。

> Cookie / token 默认只保存在本机。请勿泄露给他人。

## 功能（v1.9.0）

- **分区 UI**：顶部 Tab「京豆 / 美团 / 饿了么 / 微软」
  - **京豆**：原有京东多账号、京豆统计、养号模式、失效提醒、推送 `JD_COOKIE`
  - **美团**：多账号列表；WebView 登录提取 `token` 或手动粘贴；推送青龙 **`meituanCookie`**（纯 token，一账号一变量）
  - **饿了么**：多账号列表；**粘贴 Cookie**（主路径）；推送青龙 **`elmck`**（完整 Cookie 字符串，一账号一变量；适配 Shuying-exquisite/elm 吃货豆.py）
  - **微软**：占位「即将支持」
- Cookie 失效提醒、养号模式、京豆卡片、一键更新等（见往期版本）

## 使用

1. 安装 Release APK
2. 菜单 → 青龙配置：填写 OpenAPI（client_id / client_secret）
3. **京豆** Tab：添加账号并登录；点「更新」刷新 CK / 京豆 / 推送状态
4. **美团** Tab：添加 → WebView 登录或粘贴 token；推送到 `meituanCookie`
5. **饿了么** Tab：添加 → 粘贴完整 Cookie；推送到 `elmck`

## 饿了么 Cookie 格式

青龙变量名：`elmck`  
推荐值：`cookie2=...; unb=...; USERID=...; SID=...;`（亦可含 sgcookie/token/utdid/deviceId/umt）  
粘贴浏览器 Cookie 头时会自动精简并保留上述字段；缺 `USERID` 时用 `unb` 补齐。

## 构建

```bash
./gradlew assembleRelease
```

签名密钥放在仓库外，切勿提交。

## 注意

- 仅供个人使用
- minSdk 24 / targetSdk 34
- 美团 WebView 登录受站点风控影响，失败时请用粘贴 token
- 饿了么当前仅支持粘贴 Cookie（未做 WebView 登录）
