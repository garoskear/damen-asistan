# Damen Asistan — AGENTS.md

Native Android (Kotlin + Jetpack Compose) istemci → Termux'ta koşan `damen-gateway`'e bağlanır.
v1 kapsamı: **sadece ana sohbet içeriği.** Asistan rolü (ASSIST), pill bar, screenshot akışı v2.

## Sözleşme (kullanıcı kararları)
- WebView YOK — UI + bağlantı native Kotlin ile yazılır.
- Bağlantı HARDCODED: `ws://127.0.0.1:8787/ws?token=...` (ayar ekranı yok).
- Token düzenlenebilir JSON'dan: `/sdcard/DamenAsistan/config.json` (uygulama ilk açılışta şablonu yazar) → yoksa app içi → yoksa boş (token giriş ekranı gelir). Token Termux'ta: `cat ~/.damen-gw-token` (gateway cwd'sindeki `.damen-gw-token`).
- Kısayollar (v2 pill bar) hardcoded DEĞİL — aynı config.json'daki `shortcuts` dizisinden okunur: `[{id,label,packageName,action}]`. v1'de Termux açma + güç menüsü tanımlı, UI'ı v2'de gelir.
- Boot/güç tuşu = sistem güç menüsü (kilitle/kapat/yeniden başlat/acil durum). Android'de bu `AccessibilityService.GLOBAL_ACTION_POWER_DIALOG` ile açılır → v2'de `PowerDialogService` (erişilebilirlik izni gerekir). Root komutu yedeği: `svc power reboot` vb. deneme dışı.
- Termux tuşu = sadece uygulamayı açar (`com.termux` launch intent, komut koşmaz).
- Oturumlar gateway'deki havuzla birebir: tek aktif oturum + liste + geçiş + yeni (pi-web'deki slot sistemi). Asistan konuşmaları da normal session'dır (v2 aynı store'u kullanır).
- Model/thinking seçimi v1'de VAR (chip → sheet).
- v1'de mikrofon YOK (sonra eklenecek). Yazı + dosya eki var.
- Tasarım: Nothing çizgisi — saf siyah + beyaz + tek kırmızı `#FF0000`, `FontFamily.Monospace`. Paket `com.damen.asistan`, ad `Damen Asistan`.
- Her işte hazır template/proje baz alınır. Bu proje `damen-widget` iskeletinden klonlandı (Gradle 8.5 + AGP 8.2 + Kotlin 1.9.20 + compose-compiler 1.5.4 + minSdk 29 / target 34 + sabit keystore imza + CI).

## Mimari
```
MainActivity (Compose, Nothing tema)
 └ ChatScreen (top bar, mesaj listesi, alt bar [+][alan][gönder], drawer, model sheet)
    └ GwClient (OkHttp WebSocket, StateFlow'lar)
    └ GwConfig (hardcoded host/port + JSON token/shortcut)
```
- `GwClient`: `web/app.js` protokolüyle birebir — gelen: hello/booting/ready/state/commands/settled/switched/streaming/delta/sessions/models/notice/fatal/ping (+slot etiketi, izlenmeyen slot yoksayılır). Giden: prompt/abort/new_session/list_sessions/switch_session/list_models/set_model/set_thinking/visibility.
- Ekler: SAF `OpenMultipleDocuments` → `stageUris()` → `[{name,mime,data:base64}]` (en çok 10, 20MB kesme; sunucu 25MB). `prompt {text, files, slot}` ile gider; sunucu stage'leyip `@yol` satırı ekler.
- `messages()`: pi SDK ham mesajları — v1 basit metin özetine indirger (tool gövdesi şişirmez). Zengin kartlar (tool kartı, diff, thinking chip) v2+.

## Referanslar
- Backend: `~/damen-gateway` — `server.mjs` (WS `/ws?token`), `lib/agent-host.mjs` (slot havuzu, `stageFiles`, NATIVE_COMMANDS), `web/app.js` (protokolün asıl kaynağı — değişiklikte önce oraya bak).
- Android uyumluluğu: `SimonSchubert/Kai` (Apache-2.0) — `androidApp/src/main/AndroidManifest.xml`: ASSIST intent-filter'ı AYNI activity'de (`action.ASSIST` + `DEFAULT`), `singleTop`, `adjustResize`, `ACCESS_LOCAL_NETWORK` (SDK 37+ LAN), FileProvider, `networkSecurityConfig`. v2 ASSIST bu desenle eklenecek (şu an manifestte bilerek YOK).
- KMP'ye girilmedi: Kai 2.3GB multiplatform — bu proje saf native, widget iskeleti yeterli.

## v2 (asistan katmanı — YAPILMADI)
1. Manifest'e ASSIST intent-filter (Kai deseni, aynı MainActivity).
2. Dikey pill bar: config.json shortcuts → Termux launch intent + `GLOBAL_ACTION_POWER_DIALOG` (erişilebilirlik servisi + kullanıcı onayı).
3. `+` popup: Ekran görüntüsü (MediaProjection → asistanı gizle → çek → uygulama-içi kırpma → eke ekle) / Dosya (var olan picker).
4. Mikrofon, zengin transkript (tool kartı/diff), Termux bildirimi.

## Derleme / CI
- Lokal derleme bu cihazda yapılmaz (Android SDK yok) — CI derler: her push debug APK artifact (`APK` workflow, Gradle 8.5 elle indirilir çünkü runner 9.x AGP 8.2'yi patlatır, `fetch-depth: 0`).
- `v*` tag → imzalı release (`release.yml`, `permissions: contents: write` şart).
- İmza `app/damen-debug.keystore` (debug+release aynı, üstüne kurulum çalışır). `versionCode = commit sayısı`.

## Kritik dersler (damen-widget'tan)
- `usesCleartextTraffic="true"` şart (localhost http/ws).
- API 30+ paket görünürlüğü için `queries` (Termux) şart, yoksa launch intent bulunamaz.
- Compose `@Composable` import'ları + `collectAsState` için `lifecycle-runtime-compose` gerekir.
- AGP 8.2 signing: `getByName("debug")` düzenlenir, `create` çakışır.
