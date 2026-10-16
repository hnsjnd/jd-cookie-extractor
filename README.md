# 京东 Cookie 提取器

多账号安卓工具：分区管理 **京豆（京东）** / **美团** / **饿了么** / **夸克** / **电信**（微软占位），用独立 WebView 或粘贴方式提取 Cookie/token/凭证，并推送到青龙面板。

> Cookie / token / 服务密码默认只保存在本机。请勿泄露给他人。

## 功能（v1.10.1）

- **分区 UI**：顶部 Tab「京豆 / 美团 / 饿了么 / 夸克 / 电信 / 微软」
  - **京豆**：原有京东多账号、京豆统计、养号模式、失效提醒、推送 `JD_COOKIE`
  - **美团**：多账号列表；WebView 登录提取 `token` 或手动粘贴；推送青龙 **`meituanCookie`**（纯 token，一账号一变量）
  - **饿了么**：多账号列表；**WebView 登录获取**或**粘贴 Cookie**；推送青龙 **`elmck`**（完整 Cookie 字符串，一账号一变量；适配 Shuying-exquisite/elm 吃货豆.py）
  - **夸克**：多账号列表；**WebView 登录获取**或**粘贴** `COOKIE_QUARK`；推送青龙 **`COOKIE_QUARK`**（一账号一变量，备注=昵称）
  - **电信**：多账号列表；**粘贴** `手机号#服务密码`；推送青龙 **`chinaTelecomAccount`**（一账号一变量，备注=手机号）。会明文保存服务密码到本机与青龙，请谨慎。
  - **微软**：占位「即将支持」
- Cookie 失效提醒、养号模式、京豆卡片、一键更新等（见往期版本）

## 使用

1. 安装 Release APK
2. 菜单 → 青龙配置：填写 OpenAPI（client_id / client_secret）
3. **京豆** Tab：添加账号并登录；点「更新」刷新 CK / 京豆 / 推送状态
4. **美团** Tab：添加 → WebView 登录或粘贴 token；推送到 `meituanCookie`
5. **饿了么** Tab：添加 →「登录获取」或「粘贴」；推送到 `elmck`
6. **夸克** Tab：添加 →「登录获取」或「粘贴」；推送到 `COOKIE_QUARK`
7. **电信** Tab：添加 → 粘贴 `手机号#服务密码`；推送到 `chinaTelecomAccount`

## 夸克 Cookie 格式

青龙变量名：`COOKIE_QUARK`  
支持两种常见格式：

- `user=备注; url=https://drive-m.quark.cn/...`（推荐：完整抓包 URL，含 `kps`/`sign`/`vcode`）
- `user=; kps=; sign=; vcode=`（旧版手填参数）

一账号一变量；备注优先用昵称。

### WebView 登录

默认打开 `https://pan.quark.cn/`；登录后会探测 `drive-m.quark.cn` 的 growth/info，并拦截含 `kps`/`sign`/`vcode` 的请求（含 `act/growth/reward`）。提取成功后自动推送青龙。

**URL 注意（抓包 / 粘贴时）：**

- 新版 `…/act/growth/reward?…` 参数很多（`device_model`、`mt`、`ut`、`ds`、`xs`、`kps`、`sign`、`vcode` 等），请**整段复制**，不要拆改。
- 参数里可能有字面量 `+`、`=`、`%xx`，不要手动 URL 编解码或把 `+` 当成空格。
- 旧接口 `…/capacity/growth/info` 只要带齐 `kps`/`sign`/`vcode` 仍可用。
- WebView **不一定**能拿到 App 同款签名参数（站点风控 / 仅原生签发）；失败时请用「粘贴」备用（模拟器+系统证书抓包亦可）。

## 电信账号格式

青龙变量名：`chinaTelecomAccount`  
值：`手机号#服务密码`  
一账号一变量（推荐），备注=手机号。若粘贴多账号用 `&` 连接，应用仅取第一组。

**警告**：服务密码会保存在本机并推送到青龙，请勿分享备份或截图。

## 饿了么 Cookie 格式

青龙变量名：`elmck`  
推荐值：`cookie2=...; unb=...; USERID=...; SID=...;`（亦可含 sgcookie/token/utdid/deviceId/umt）  
WebView 登录或粘贴浏览器 Cookie 头时会自动精简并保留上述字段；缺 `USERID` 时用 `unb` 补齐。

登录页默认打开 `https://h5.ele.me/`；站点可能跳转到支付宝/淘宝通行证。若 WebView 无法登录，请用「粘贴」备用。

## 构建

```bash
./gradlew assembleRelease
```

签名密钥放在仓库外，切勿提交。

## 注意

- 仅供个人使用
- minSdk 24 / targetSdk 34
- 美团 / 饿了么 / 夸克 WebView 登录受站点风控影响，失败时请用粘贴备用
- 切勿在日志或截图中暴露 Cookie / token / 服务密码
