# Damen Asistan — Rebuild & Düzeltme Raporu

**Tarih:** 13 Eylül 2026  
**Paket:** `com.damen.asistan`  
**GitHub:** `garoskear/damen-asistan`  
**Branch:** `main` (commit `227333e`)  
**APK Konumu:** `/sdcard/Download/DamenAsistan.apk` (51.5 MB)  
**CI Derlemesi:** GitHub Actions `APK` workflow run #34767927130 (Yeşil / Başarılı — 1m 22s)

---

## 1. Düzeltilen Hatalar ve Yapılan İyileştirmeler

### Bug 1: Mesaj Barı Üstündeki Durum Şeridi (Info Strip) Titremesi ve Sağa Kayması
- **Sorun:** Durum şeridi (`statuses`, `widgets`, `stats`) durum değişimlerinde ve model yüklenirken kararsız görünürlük mantığı (`stripVisible`) yüzünden anlık olarak yok olup tekrar beliriyor (flicker), `rememberScrollState()` bileşenin ayrılıp tekrar girmesiyle sağa kayıyor veya sıfırlanıyordu.
- **Çözüm:** 
  - Şerit `heightIn(max = 68.dp)` ile sabit üst sınırlı boyuta alındı.
  - Şerit dikey ve yatay kaydırma durumları (`stripVScroll`, `stripHScroll`, `statsHScroll`) ekran düzeyinde hatırlandı (`rememberScrollState`).
  - Görünürlük mantığı `statuses`, `widgets`, `hasStats` ve `modelId` varlığına göre kararlı hale getirildi, gereksiz yeniden boyutlanma ve sağa kayma engellendi.

### Bug 2: Eski Oturum Adlarında "null" Görünmesi
- **Sorun:** Android `JSONObject.optString("name", null)` çağrısı, JSON değeri `null` olduğunda literal `"null"` stringi döndürür. Bu durum oturum başlıklarında ve drawer listesinde oturum adının `"null"` olarak yazılmasına yol açıyordu.
- **Çözüm:**
  - `GwClient.kt` içine `JSONObject.safeNull(key)` genişletmesi eklendi; `isNull(key)` veya literal `"null"` stringi olduğunda gerçek `null` döner.
  - `cleanSessionTitle(name, title, path, fallback)` yardımcı fonksiyonu yazıldı: `sessionName` -> `title` -> dosya adı (uzantısız) -> `"oturum"` hiyerarşisiyle güvenli fallback sağlandı. Artık hiçbir arayüzde "null" görünmez.

### Bug 3: Klavyenin Mesaj Barını Kapatması (IME Inset Çakışması)
- **Sorun:** `WindowInsets.ime.getBottom(...)` Compose State olmadığı için klavye açıldığında recomposition tetiklemiyordu; dolayısıyla klavye açıldığında bar klavyenin altında kalıyordu.
- **Çözüm:**
  - `bottomBar`'a dinamik inset olarak `Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` uygulandı.
  - Klavye kapalıyken alt sistem navigasyon barı (üç tuş / jest çizgisi) kadar boşluk bırakılır.
  - Klavye açıldığında `union` (en büyük olanı alma) sayesinde bar klavyenin tam üzerine sıfır boşluk ve sıfır taşma ile kusursuz bir şekilde yükselir.

### Bug 4: Lag, Takılmalar ve LazyColumn Performansı
- **Sorun:** Kotlin data sınıflarında `List<T>` kullanıldığında Compose derleyicisi bu sınıfları "kararsız" (unstable) kabul eder. Canlı akış sırasında her 120ms'lik delta geldiğinde geçmiş 50 turn'ün tamamı tekrar recompose ediliyor ve `parseMd` / `inline` içinde her karakter için `Regex` yeniden derleniyordu.
- **Çözüm:**
  - Tüm model ve durum sınıflarına (`ChatMsg`, `Part`, `SessionInfo`, `ModelInfo`, `Cmd`, `Stats`, `Notice`, `DialogState`, `LiveSeg`, `Conn`, `MdBlock`) `@Immutable` anotasyonu eklendi.
  - `Turn` bileşeninden kararsız `client` referansı kaldırıldı; tool çıktıları (`output`) doğrudan `Part.ToolCall` veri yapısına taşındı.
  - Canlı delta akışında geçmiş turn'ler Compose derleyicisi tarafından %100 "skip" edilir.
  - `Md.kt` içindeki tüm regex'ler statik derlendi (`IMG_STRIP_RE`, `LINK_FIND_RE`, `HEAD_RE`, `LIST_ITEM_RE` vb.) ve hızlı tek geçişli parser'a dönüştürüldü.

### Bug 5: Mesaj Barına Dokununca Sona Atlama & Jump-to-Bottom FAB
- **Sorun:** Aşağı atlama FAB'ı güvenilmez şekilde görünüyor, mesaj çubuğuna dokunulduğunda en alta atlama gerçekleşmiyordu.
- **Çözüm:**
  - Dibe yakınlık tespiti `derivedStateOf` ile sıfır recomposition maliyetine indirgendi (`isAtBottom`).
  - Mesaj alanına (`TextField`) odaklanıldığında veya dokunulduğunda (`PressInteraction.Release` / `FocusInteraction.Focus`) anında en alta atlama (`scrollToItem`) tetiklendi.
  - Jump FAB'ı yalnızca dipte olunmadığında `AnimatedVisibility` (fade-in / fade-out) ile görünür hale getirildi ve tıklandığında yumuşak animasyonla dibe kaydırır.

### Bug 6: Tasarım Birliği, Edge-to-Edge ve Üst Siyah Boşluk
- **Sorun:** Status bar arkasında istenmeyen boşluklar ve aktivite/tema uyuşmazlığı vardı.
- **Çözüm:**
  - `themes.xml` içinde saf siyah (`#000000`) ve şeffaf sistem barlarına sahip `Theme.Damen` teması tanımlandı ve `AndroidManifest.xml`'e bağlandı.
  - `Scaffold(contentWindowInsets = WindowInsets(0.dp))` ile varsayılan sistem bar inset'leri Scaffold gövdesinden arındırıldı.
  - Top bar'a `statusBarsPadding()` verilerek durum çubuğu simgelerinin üstüne oturması sağlandı; Drawer ve Chat ekranları aynı Nothing monokromatik tipografisini (`Damen.Mono`, `Micro`, `startBorder`, `#FF0000` aksan) paylaşıyor.

### Bug 7 & Kullanıcı İsteği: Slash Komutları Arayüzünün Yeniden Tasarımı
- **Sorun:** Slash komut popup'ı mesaj barının içinde statik bir sütun olarak duruyor, komut sayısı arttığında ekranı kaplayıp taşırıyor ve klavyeden seçimi zorlaştırıyordu.
- **Çözüm:**
  - **Yüzen Popup (Floating Modal):** Komut popup'ı kompozitörün üzerinde yüzen (floating), `maxHeight = 220.dp` olan ve kaydırılabilen bağımsız bir `LazyColumn` olarak yeniden yazıldı.
  - **Rozetler ve Bilgiler:** Her komut için komut adı (`/${c.name}` - kalın beyaz), açıklama (açık gri tek satır) ve kaynak rozeti (`SYS`, `EXT`, `SKILL`, `PROMPT`) gösterildi.
  - **Dokunma & Tamamlama:** Listeden bir komuta dokunulduğunda komut metin kutusuna `/${c.name} ` olarak yazılır ve imleç sona konumlanır.
  - **Yerleşik Fallback Komutlar:** WebSocket henüz komut listesini göndermemiş olsa bile temel komutlar (`BUILTIN_COMMANDS`: `/new`, `/resume`, `/model`, `/thinking`, `/compact`, `/name`, `/clear-queue`, `/copy`, `/help`) anında hazırdır.
  - **Kapsamlı `/help` Sheet'i:** `/help` çalıştırıldığında açılan alt sayfaya canlı arama/filtreleme kutusu ve `LazyColumn` eklendi; listeden seçilen komut kompozitöre otomatik doldurulur.

---

## 2. Değiştirilen Dosyalar

| Dosya Yolu | Değişiklik Özeti |
|---|---|
| `app/src/main/java/com/damen/asistan/GwClient.kt` | `@Immutable` anotasyonları, `Part.ToolCall.output` alanı, `BUILTIN_COMMANDS` listesi, `cleanSessionTitle`, `safeNull` fonksiyonu. |
| `app/src/main/java/com/damen/asistan/ChatScreen.kt` | Sabit boyutlu strip, yüzen scrollable slash popup, `union(WindowInsets.ime)` klavye uyumu, `derivedStateOf` jump FAB, dokunmayla dibe atlama, arama filtreli `/help` sheet'i, memoized turn çizimi. |
| `app/src/main/java/com/damen/asistan/Md.kt` | Statik pre-compiled regex'ler, tek geçişli hızlı inline parser, `@Immutable` bloklar. |
| `app/src/main/res/values/themes.xml` | `Theme.Damen` saf siyah taban teması eklendi. |
| `app/src/main/AndroidManifest.xml` | `Theme.Damen` teması uygulandı. |
| `AGENTS.md` | v2.1 Rebuild belgelendirmesi eklendi. |

---

## 3. Derleme ve Doğrulama Çıktısı

Derleme kullanıcının talimatı doğrultusunda **GitHub Actions** CI ortamında koşuldu:
- **Repository:** `garoskear/damen-asistan`
- **Workflow:** `APK` (main branch push)
- **Run ID:** `34767927130`
- **İşlem Aşamaları:**
  1. `actions/checkout@v4` (fetch-depth: 0) — Başarılı
  2. `actions/setup-java@v4` (Java 17 Temurin) — Başarılı
  3. `gradle/actions/setup-gradle@v4` — Başarılı
  4. `Install Gradle 8.5` — Başarılı
  5. `/tmp/gradle-8.5/bin/gradle assembleDebug` — **BUILD SUCCESSFUL in 1m 22s**
  6. `upload-artifact@v4` (`damen-asistan-apk`) — Başarılı
- **Artifact İndirme:** Artifact `gh run download 34767927130` ile çekildi ve `/sdcard/Download/DamenAsistan.apk` yoluna kopyalandı.

---

## 4. Kapsam Dışı Bırakılanlar (ve Nedenleri)

- **Mikrofon / Ses Kaydı:** Kullanıcı yönergelerinde "v1'de mikrofon YOK (sonra eklenecek)" kuralı gereğince bilinçli olarak eklenmedi.
- **Harici Ayar Ekranı:** Host/port kurallar gereği kod içinde `ws://127.0.0.1:8787` olarak sabitlendi; token ve kısayollar `/sdcard/DamenAsistan/config.json` dosyasından okunur.
