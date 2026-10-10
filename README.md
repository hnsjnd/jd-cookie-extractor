# JD Cookie Extractor

简洁安卓工具：用 WebView 打开京东手机版，登录后一键提取 `pt_key` 和 `pt_pin`。

## 功能

- WebView 打开 https://m.jd.com/
- 登录后点击按钮提取 Cookie
- 自动格式化为 `pt_key=xxx;pt_pin=xxx;`
- 一键复制到剪贴板

## 使用

1. 用 Android Studio 打开本项目
2. 运行到手机
3. 在 App 内用手机号验证码登录京东
4. 点击「提取 Cookie」→「复制」
5. 贴到青龙的 `JD_COOKIE` 环境变量

## 注意

- 仅供个人使用，Cookie 请勿泄露
- 建议用手机号验证码登录，有效期更长
