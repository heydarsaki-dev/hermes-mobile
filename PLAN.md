# Hermes Mobile — طرح فنی

## ۱. کشف API هرمس (نسخه ۰.۱۹.۰)

### احراز هویت
- توکن از متغیر محیطی `HERMES_DASHBOARD_SESSION_TOKEN` می‌آید.
- دو روش ارسال: هدر `X-Hermes-Session-Token` یا `Authorization: Bearer <token>`
- کوئری `?token=` فقط برای دانلود فایل و WebSocket.

### دو مسیر چت
| مسیر | توضیح | کاربرد در اپ |
|---|---|---|
| `/api/pty` | ترمینال PTY خام (ANSI) | نمای ترمینال کلاسیک |
| `/api/ws` | JSON-RPC 2.0 | **چت اصلی اپ** |
| `/api/events` | برادکست رویدادها | وضعیت |
| `/api/console` | کنسول امن | کنسول مدیریت |

### پروتکل `/api/ws`
درخواست: {"jsonrpc":"2.0","id":"1","method":"prompt.submit","params":{"session_id":"...","text":"..."}}
رویداد: {"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"text":"..."}}}

رویدادها: gateway.ready, turn.start, message.delta, reasoning.delta,
thinking.delta, tool.start, tool.complete, tool.output_risk, tool.generating,
approval.request, message.complete, turn.error

### متدهای کلیدی (۱۱۵ متد)
- چت: prompt.submit, session.interrupt, session.steer, session.undo, session.compress
- مدل/پرووایدر: model.options, config.get, config.set, model.save_key, model.disconnect
- ابزارها: tools.list, tools.configure, tools.show, toolsets.list
- نشست: session.create/list/resume/delete/branch/history/status/usage/title
- مهارت: skills.manage, skills.reload
- پلاگین: plugins.list, plugins.manage
- سایر: cron.manage, shell.exec, process.list, browser.manage, config.show

### REST API (۲۲۷ مسیر)
مدل، پرووایدر، کرون، MCP، حافظه، لاگ، فایل، گیت، بیلینگ، پیام‌رسان‌ها.

## ۲. معماری اپ
core/net (OkHttp، REST، WebSocket JSON-RPC)
core/datastore (تنظیمات)
core/util (تاریخ شمسی، فرمت)
data/model (DTO)، data/repo، data/session
ui/theme, ui/components, ui/connect, ui/chat, ui/sessions, ui/model, ui/tools, ui/settings

## ۳. پشته فنی
Kotlin 1.9.22, AGP 8.11.0, compileSdk 36, Java 17
Jetpack Compose + Material 3 (RTL فارسی), OkHttp, DataStore, Navigation Compose

## ۴. صفحات
اتصال، چت، نشست‌ها، مدل، پرووایدر، ابزارها، مهارت/پلاگین، کرون، ترمینال، تنظیمات

## ۵. فارسی‌سازی
تمام رشته‌ها فارسی، جهت RTL، اعداد با تقویم شمسی
