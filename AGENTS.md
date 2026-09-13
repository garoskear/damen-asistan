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

## v2.2 Asistan yükseltmesi (2026-09-13)
- **Otomatik ekran görüntüsü:** Asistan her açıldığında `PowerService.captureScreen` (erişilebilirlik `takeScreenshot`, API 30+) arayüz gelmeden sessizce kare yakalar → `cacheDir/auto_shot.png`. Kullanıcı `⛶` tuşu veya `+` menüsündeki "Ekran Görüntüsü Ekle" ile tek dokunuşla eke ekler; gönderimde/sonlandırmada tmp dosyası silinir. Erişilebilirlik kapalıysa eski MediaProjection + CropActivity akışı yedekte durur.
- **Mikrofon:** Asistan + ana sohbet kompozitörüne `SpeechRecognizer` tabanlı 🎙 tuşu (RECORD_AUDIO izni, kısmi sonuç, dinlerken nabız animasyonu); tanınan metin alana eklenir, gönderimde dinleme durur.
- **Akıcı asistan→uygulama geçişi:** WS bağlantısı `onCreate`'te önceden kurulur; gönderimde 2sn hızlı bekleme + prompt fırlatılıp `FLAG_ACTIVITY_SINGLE_TOP|CLEAR_TOP` + fade geçişiyle ana sohbete anında geçilir (önceki 15sn+ beklemeli akış kaldırıldı).
- **Turn gruplama:** pi SDK thinking/toolCall/text'i ayrı mesajlara böldüğü için her parça ayrı numaralı mesaj gibi duruyordu. Ardışık aynı roldeki mesajlar artık `FeedItem.Group` ile tek numaralı PI/SEN bloğunda birleşir (`ChatScreen`); bildirim çapaları grup bitiş indeksine göre dizilir.

## v2.1 Rebuild (2026-09-13, stabilizasyon ve parite)
- **Info Strip (Bug 1):** `statuses`, `widgets`, `stats` şeridi sabit üst sınırlı boyutta (`heightIn(max = 68.dp)`), dikey ve yatay kaydırma durumları ekran seviyesinde sabitlendi; titreme ve sağa kayma önlendi.
- **Session Adları "null" (Bug 2):** Android `JSONObject.optString`'in literal `"null"` dönmesi `safeNull` ve `cleanSessionTitle` ile tamamen engellendi; oturum adı yoksa dosya adı veya güvenli fallback gelir.
- **Klavye / IME Mesaj Barı (Bug 3):** `WindowInsets.navigationBars.union(WindowInsets.ime)` ile klavye açıkken tam klavye üstüne kalkar, kapalıyken sistem navigasyon barı üstünde durur (çakışma ve boşluk sıfırlandı).
- **Lag ve Akıcılık (Bug 4):** Bütün model ve mesaj sınıfları `@Immutable` yapıldı (Compose turn skipping aktif — canlı akış sırasında geçmiş turn'ler yeniden hesaplanmaz); `Turn` içinden kararsız `client` referansı kaldırıldı (çıktı doğrudan `Part.ToolCall` içinde taşınır); `Md.kt` regex'leri statik derlendi, hızlı tek-geçiş inline parser yazıldı.
- **Jump FAB & Bar Dokunma (Bug 5):** `isAtBottom` tespiti `derivedStateOf` ile sıfır maliyetle yapılır; mesaj çubuğuna dokunulduğu/odaklanıldığı anda liste en alta zıplar; FAB sadece dipte değilken belirir.
- **Tasarım Birliği & Edge-to-Edge (Bug 6):** `Theme.Damen` oluşturuldu (`themes.xml` ve `AndroidManifest.xml`), status bar ve navigation bar şeffaf, saf siyah arka plan ile üst siyah boşluk kaldırıldı; drawer ve chat aynı Nothing monokrom dilini konuşur.
- **Slash Komutları UI & UX:** Mesaj barı üzerinde yüzen (floating), kaydırılabilir `LazyColumn` popup (220dp max), SYS/EXT/SKILL/PROMPT rozetleri, `BUILTIN_COMMANDS` ile çevrimdışı fallback, arama filtreli `/help` sheet'i ve argümanlı/argümansız komut akışı tamamlandı.

- Tema tokenları `Theme.kt` (style.css :root birebiri) + TR/EN `Lang` (i18n.js birebiri).
- `Md.kt`: mini markdown (başlık/kod/liste/alıntı/tablo/chip) + satır içi kod/kalın/link.
- `GwClient`: yapısal part'lar (text/thinking/toolCall/image, bashExecution), live segment sırası,
  tool çıktı eşleştirme, edit LCS diff, komut/stats/queue/status/widget/dialog/toast/notices.
- `ChatScreen`: topbar+rec, turn, tool kartı (süre hapı/önizleme/detay), bash, thinking,
  jump FAB, kuyruk, strip+stats, kompozitör (slash/tray/steer/stop), drawer (meta/aktif/●),
  model sheet (arama+seviye chip), dialog sheet (select/confirm/input/editor/help),
  taslak+geçmiş (SharedPreferences), TR/EN, iyimser @satır balonu.
- Üst boşluk: `enableEdgeToEdge` + şeffaf barlar + manuel inset.
- Kurulan skill'ler: `hallmark` (tasarım), `mobile-android-design` (Compose/M3).
- Bilinen eksik: donanım klavyesiz geçmiş gezinmesi (sadece fiziksel ↑/↓), pencere başına
  thinking açık/kapalı durumu (web'de live'da seg'de yaşar — burada da seg'de).

## Asistan katmanı (2026-09-13, orijinal vizyonun tamamı)
- `AssistantActivity` (ASSIST intent, translucent, singleInstance, recents dışı): altta
  `[+] [alan] [gönder]`, en sağda dikey pill (config.json shortcuts), `+` popup'ı
  (Ekran görüntüsü / Dosya). Boş alana dokun = kapat.
- Screenshot akışı: MediaProjection izni → asistan gizlenir (`hidden`) → `CaptureService`
  (foreground, mediaProjection tipi, API 34 izni) tek kare yakalar → `SHOT` yayını →
  asistan `CropActivity`'yi sonuç için açar → sürükle-çiz/taşı kırpma → Ekle (eke düşer).
- Gönderim: token + hello beklenir → kayıtlı `assistant_session` varsa switch, yoksa
  new_session → prompt → session yolu saklanır → ana sohbet açılır. Asistan konuşmaları
  normal session'dır (aynı store).
- Pill: `termux` → paket launch; `power_dialog` → `PowerService` (erişilebilirlik,
  GLOBAL_ACTION_POWER_DIALOG), kapalıysa ayar ekranı + toast. Kısayollar config.json'dan.
- Kai desenleri: MainActivity'de SEND (text/plain) paylaşımı alana doldurur; ASSIST/SEND
  tüketilince action/extra temizlenir (rotasyonda tekrar uygulanmaz).
- İzinler: FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PROJECTION; erişilebilirlik xml'i.
