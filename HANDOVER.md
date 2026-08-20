# SwiftVPN — سند تحویل و ادامه‌ی کار

> این سند برای این نوشته شده که هر کسی — خودت چند ماه بعد، یک برنامه‌نویس دیگر، یا
> یک دستیار هوش مصنوعی دیگر — بتواند بدون خواندن کل تاریخچه‌ی گفتگو کار را ادامه دهد.
>
> **مهم‌ترین بخش این سند، فصل «تله‌ها» است.** هر موردِ آن یک باگ واقعی است که
> ساعت‌ها وقت گرفت. اگر فقط یک فصل را می‌خوانی، همان را بخوان.

آخرین به‌روزرسانی: ۲۰۲۶-۰۷-۲۷

---

## ۱. این پروژه چیست

یک کلاینت VPN شخصی برای اندروید، ساخته‌شده برای استفاده‌ی خودِ مالک — **نه** برای
انتشار در Google Play. انگیزه‌ی اصلی: کاربر از VPN Client Pro استفاده می‌کرد و همه‌چیزش
پولی بود؛ تنها چیزی که واقعاً می‌خواست یک **دکمه‌ی اتصال/قطع در تب‌بار (Quick Settings)**
بود تا لازم نباشد اپ را باز کند.

- **package:** `ir.swiftvpn`
- **زبان:** Kotlin + Jetpack Compose، Material 3
- **معماری:** یک روتر (`VpnEngine`) که چند موتور VPN را پشت یک اینترفیس یکسان جمع می‌کند
- **زبان رابط:** فارسی و انگلیسی (`values/` و `values-fa/`)، با پشتیبانی کامل RTL
- **ABI:** فقط `arm64-v8a` (برای کوچک ماندن APK)
- **minSdk 24 · targetSdk 36 · compileSdk 36**

---

## ۲. ⚠️ دو شاخه‌ی جدا وجود دارد — این را اول بفهم

پروژه **دو نسخه‌ی موازی** دارد که از هم واگرا شده‌اند. این عمدی بود: کاربر بعد از
اضافه‌شدن Xray به نسخه‌ی دو‌پروتکلی برگشت، پس هر دو باید سالم می‌ماندند.

| | شاخه A — دو پروتکل | شاخه B — سه پروتکل |
|---|---|---|
| **پوشه** | `SwiftVPN-2p/SwiftVPN2/` | `SwiftVPN2/` |
| **پروتکل‌ها** | OpenVPN + WireGuard | OpenVPN + WireGuard + Xray |
| **آخرین نسخه** | `SwiftVPN-2p-v3.1` | `SwiftVPN-v2.8` |
| **حجم APK** | ۳۱ مگابایت | ۴۲ مگابایت |
| **وضعیت** | پایدار، آخرین کاری که کاربر خواست | Xray هنوز روی دستگاه تأیید نشده |

### فایل‌هایی که فقط در شاخه A هستند
```
engine/ZipImport.kt                    وارد کردن گروهی از فایل zip
ui/components/DeleteConfirmDialog.kt   تأیید حذف
```
به‌علاوه در `ProfileListScreen.kt`: **انتخاب چندتایی با نگه‌داشتن + حذف گروهی**.

### فایل‌هایی که فقط در شاخه B هستند
```
xray/XrayVpnService.kt                 VpnService خودمان برای Xray
engine/XrayEngine.kt                   درایور
engine/XrayRuntime.kt                  بوت‌استرپ یک‌بارِ gomobile
engine/XrayStore.kt                    ذخیره‌ی لینک‌های اشتراک
engine/XraySubscription.kt             سابسکریپشن (به‌روزرسانی دستی)
engine/XrayTester.kt                   تست تاخیر + کشور/آی‌پی + سرعت دانلود
engine/xray/XrayShareLink.kt           پارسر vless/vmess/trojan/ss
engine/xray/XrayConfig.kt              ساخت JSON اکسری
engine/xray/XrayProbeConfig.kt         کانفیگ موقت برای probe
engine/xray/XrayOutbound.kt            مدل نرمال‌شده
ui/screens/XraySettingsScreen.kt
ui/screens/SubscriptionsScreen.kt
```
به‌علاوه: **چیپ‌های فیلتر** و **badge تاخیر** در لیست.

### 🔴 اگر بخواهی این دو را یکی کنی
شاخه A دو قابلیت دارد که B ندارد (zip و حذف گروهی)، و B سه قابلیت دارد که A ندارد
(Xray، فیلتر، تست سرور). **فیلترِ چیپی در هر دو شاخه هست** ولی در A فقط `Name` و
`Type` دارد و در B هم همان. مسیر پیشنهادی برای ادغام:

1. از **A** شروع کن (پایدارتر است و کاربر رویش ماند).
2. `ZipImport.kt` و `DeleteConfirmDialog.kt` و منطق انتخاب را نگه دار.
3. فایل‌های `xray*` را از B بیاور.
4. `Protocol` enum را به سه مقدار برسان و **هر `when` روی آن را کامل کن** —
   کامپایلر Kotlin این‌ها را پیدا می‌کند، به خطاهایش اعتماد کن.
5. در `VpnEngine`: `stopOtherEngine` باید هر دو موتور دیگر را بخواباند، نه یکی.
6. callbackهای Xray را روی `_activeProtocol == XRAY` شرطی کن (دلیلش در فصل تله‌ها).

---

## ۳. معماری

```
┌──────────────────────────────────────────────────┐
│  پروسه‌ی اپ (ir.swiftvpn)                        │
│  ├─ MainActivity + Compose UI                    │
│  ├─ MainViewModel            حالت UI             │
│  ├─ VpnTileService           دکمه‌ی تب‌بار        │
│  ├─ VpnEngine          ★ روتر + منبع یگانه‌ی حالت │
│  │   ├─ (OpenVPN) ─── AIDL ──┐                   │
│  │   ├─ WireGuardEngine       │  GoBackend        │
│  │   │      libwg-go.so       │                   │
│  │   └─ XrayEngine   (فقط B)  │                   │
│  │        └─ XrayVpnService   │  TUN + هسته       │
│  │             libgojni.so    │  (gvisor داخلی)   │
└──────────────────────────────┼───────────────────┘
                               │ IServiceStatus
┌──────────────────────────────▼───────────────────┐
│  پروسه‌ی :openvpn                                │
│  ├─ OpenVPNService           تونل واقعی          │
│  └─ OpenVPNStatusService     پل وضعیت            │
│      libovpn3.so · libopenvpn.so                 │
└──────────────────────────────────────────────────┘
```

### قانونی که کل طراحی را شکل داد
اندروید فقط **یک VpnService فعال در هر لحظه** اجازه می‌دهد. پس:
- شروع هر موتور، اول بقیه را می‌خوابانَد (`stopOtherEngine`)
- `_activeProtocol` ثبت می‌کند کدام موتور تونل را در دست دارد
- **همه‌ی callbackهای همه‌ی موتورها روی `_activeProtocol` شرطی‌اند**

### سه ناهمخوانی که در لایه‌ی روتر جذب می‌شوند
| | OpenVPN | WireGuard | Xray |
|---|---|---|---|
| **آمار ترافیک** | پوش (`ByteCountListener`) | پول (`getStatistics`) | پول (`queryStats`) |
| **اطلاعات تونل** | استخراج از لاگ | از کانفیگ | از کانفیگ |
| **مالک VpnService** | موتور، پروسه‌ی جدا | کتابخانه (`GoBackend`) | **خودمان** |

---

## ۴. 🔴 تله‌ها — این فصل را حتماً بخوان

هر مورد یک باگ واقعی است که رخ داد. اگر دست به این نقاط زدی، اول توضیح را بخوان.

### ۴.۱ `SwiftVpnApp` باید `ICSOpenVPNApplication` را extend کند
اگر `Application` را مستقیم extend کنی، چهار بوت‌استرپ موتور از دست می‌رود و
`GlobalPreferences.getInstance()` استثنا می‌اندازد: `"Global preferences instance is
not set"` — و **هیچ تونلی هرگز بالا نمی‌آید**.

### ۴.۲ `libosslspeedtest.so` را هرگز حذف نکن
ظاهراً بلااستفاده است (ابزار تست سرعت). ولی `NativeUtils` در static initializer
**بی‌قید‌و‌شرط** لودش می‌کند. گاردِ `!BuildConfig.FLAVOR.equals("skeleton")` گول‌زننده
است چون نام واقعی flavor ترکیبی است: **`skeletonOvpn23`**. حذفش → کرش در startup.
> در v2.3 این اشتباه را کردم و v2.4 اصلاحش کرد.

### ۴.۳ دو activity-alias اجباری در manifest
موتور دو مسیر را **هارد‌کد** کرده و اگر نباشند پروسه‌ی `:openvpn` کرش می‌کند:
```xml
<activity-alias android:name=".activities.MainActivity"    ... />
<activity-alias android:name=".activities.CredentialsPopup" ... />
```

### ۴.۴ core library desugaring اجباری است
`com.wireguard.config.InetEndpoint` از `java.time` (API 26) استفاده می‌کند و minSdk ما
۲۴ است. بدون desugaring اپ **بی‌مشکل کامپایل و نصب می‌شود** و بعد روی اندروید ۷ سر
اولین resolve آدرس `NoClassDefFoundError` می‌دهد — خرابی در زمان اتصال، نه build.

### ۴.۵ فقط شاخه B — پلِ cgo: callback باید فوراً برگردد
**این علت کرشِ دکمه‌ی اتصال بود.** هسته‌ی Xray دو callback را **همگام از داخل
`startLoop`** صدا می‌زند، روی نخِ خودش، در حالی که فریمِ Go روی استک است. اگر در آن
callback کار واقعی بکنی (نوشتن در StateFlow → لمس UI)، دوباره وارد پل JNI می‌شوی و
پروسه در `runtime.cgocallback` می‌میرد — **بدون هیچ استثنای جاوا**.
> **قاعده:** رشته را بگیر، به نخِ خودت بسپار، برگرد. تمام.

### ۴.۶ فقط شاخه B — مالکیت TUN fd
`pfd.fd` فقط عدد را می‌دهد و `ParcelFileDescriptor` همچنان مالک است → دو مالک.
باید `detachFd()` بزنی. **و بعد خودت ببندی**، چون `AndroidTun.Close()` در اکسری
روی اندروید دقیقاً `return nil` است و هیچ‌جا fd را نمی‌بندد (برخلاف نسخه‌ی لینوکس).
اگر نبندی، هر اتصال یک descriptor نشت می‌کند.

### ۴.۷ فقط شاخه B — `startLoop` متغیر محیطی پروسه را دست می‌زند
`StartLoop` بی‌قید `os.Setenv("xray.tun.fd", …)` را در **کل پروسه** صدا می‌زند. پس یک
probe با fd صفر می‌تواند متغیرِ تونلِ زنده را خراب کند. راه‌حل: **تست وقتی تونل وصل
است اجرا نمی‌شود** + یک mutex که شروع هسته‌ها را سریالی می‌کند.

### ۴.۸ فقط شاخه B — ساخت/تخریب سریع هسته‌های Go کرش می‌دهد
«تست همه» برای هر سرور یک هسته می‌ساخت و می‌بست، پشت سر هم → کرش native.
راه‌حل: mutex + **۳۰۰ میلی‌ثانیه فاصله** بین بستن یکی و ساختن بعدی.

### ۴.۹ ذخیره‌ی enum با نام، نه ordinal
`ThemeMode` را با ordinal ذخیره می‌کردم؛ وقتی `SYSTEM` حذف شد همه‌ی اعداد جابه‌جا شدند
و مقدار ذخیره‌شده بی‌صدا معنایش عوض شد. حالا `themeMode` و `protocolFilter` **با نام**
ذخیره می‌شوند.

### ۴.۱۰ endpoint واقعی در `mConnections[]` است نه `mServerName`
`VpnProfile.clearDefaults()` مقدار `mServerName` را روی رشته‌ی `"unknown"` می‌گذارد.
آدرس واقعی در `mConnections.firstOrNull { it.mEnabled }` است.

### ۴.۱۱ قطع اتصال OpenVPN با Intent کار نمی‌کند
`OpenVPNService.onStartCommand` هیچ‌وقت `DISCONNECT_VPN` را نمی‌خواند. باید به سرویس
bind کنی و `stopVPN(false)` را از AIDL صدا بزنی — روی نخِ پس‌زمینه.

### ۴.۱۲ کرش native دیالوگ کرشِ اپ را رد می‌کند
`Thread.setDefaultUncaughtExceptionHandler` فقط استثناهای جاوا را می‌گیرد. یک سیگنال
native پروسه را بی‌صدا می‌کشد. راه‌حل: `CrashReporter.breadcrumb()` قبل از ورود به کد
native یک ردِپا روی دیسک می‌گذارد و بعدش پاک می‌کند؛ ردپای باقی‌مانده در اجرای بعدی
به‌عنوان «Native crash during: …» نشان داده می‌شود.

### ۴.۱۳ ریپل مربعی دور کنترل گرد
`.clip(Shape)` **باید قبل از** `.clickable` بیاید، وگرنه هاله‌ی مربعی می‌بینی.

---

## ۵. قابلیت‌ها

مشترک در هر دو شاخه:
- دکمه‌ی تب‌بار (Quick Settings) — دلیل وجود اپ
- اسپیدمتر با نمودار ۶۰ ثانیه‌ای دریافت/ارسال
- نوتیفیکیشن با نرخ و مدت اتصال، برای هر دو پروتکل
- تم روشن/تاریک
- **گزارش عیب‌یابی** (آیکون 📋 در نوار بالا) — روی دیسک، از هر دو پروسه، با کپی و اشتراک‌گذاری
- QR: اسکن و نمایش
- چیپ‌های فیلتر: `Sort` و `Type`
- ویرایش کامل تنظیمات پروفایل

فقط A: **وارد کردن zip** (گروهی)، **حذف با نگه‌داشتن + انتخاب چندتایی**
فقط B: **Xray** (vless/vmess/trojan/ss)، **تست تاخیر + کشور/آی‌پی + سرعت**، **سابسکریپشن**

---

## ۶. محیط ساخت

```
JDK 21 (Amazon Corretto)
Android SDK 36 + build-tools 35.0.0
NDK 28.2.13676358
SWIG 4.x        ← برای ماژول openvpnengine لازم است
Go 1.26.5       ← فقط برای بازساختن AAR اکسری (شاخه B)
AGP 8.13.2 · Kotlin 2.3.10 · Compose BOM 2025.12.01
```

### وابستگی‌ها
```
com.wireguard.android:tunnel:1.0.20260102   (Apache-2.0، libwg-go.so آماده)
com.google.zxing:core:3.5.3                 (QR encoder)
com.journeyapps:zxing-android-embedded:4.3.0 (QR scanner)
com.android.tools:desugar_jdk_libs:2.1.5    ← اجباری، تله ۴.۴
app/libs/libv2ray.aar                        (فقط B — از سورس ساخته شده)
```

### ساختن
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease

# امضا (نسخه‌های تحویلی با کلید debug امضا شده‌اند تا روی هم نصب شوند)
zipalign -p -f 4 app-release-unsigned.apk a.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out app-release-signed.apk a.apk
```

> **هشدار کلید:** همه‌ی APKها با کلید **debug** امضا شده‌اند. اگر روزی با کلید دیگری
> امضا کنی، اندروید اجازه‌ی به‌روزرسانی نمی‌دهد و کاربر باید اپ را حذف کند — یعنی همه‌ی
> پروفایل‌ها از دست می‌رود. برای پایداری بلندمدت یک keystore ثابت بساز و نگهش دار.

### بازساختن AAR اکسری (فقط شاخه B)
راهنمای کامل در `SwiftVPN2/docs/BUILDING_XRAY.md`. خلاصه:
```bash
git clone --depth 1 https://github.com/2dust/AndroidLibXrayLite.git
cd AndroidLibXrayLite
MOBVER=$(grep 'golang.org/x/mobile' go.mod | grep -v indirect | awk '{print $2}')
go install golang.org/x/mobile/cmd/gomobile@$MOBVER
go install golang.org/x/mobile/cmd/gobind@$MOBVER
gomobile init
gomobile bind -target=android/arm64 -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' -o libv2ray.aar .
```
`-checklinkname=0` اجباری است، وگرنه لینکر Go 1.23+ خطا می‌دهد.

**نکته‌ی مهم:** فایل‌های `geoip.dat` و `geosite.dat` (۲۷ مگابایت) را از AAR حذف کردم
چون کانفیگ ما هیچ قانون geo ندارد. اگر روتینگ geo اضافه کردی، برشان گردان.

---

## ۷. لایسنس — قبل از فکر کردن به انتشار بخوان

| بخش | لایسنس |
|---|---|
| ics-openvpn (`openvpnengine`) | **GPL-2.0 با شرایط اضافی** |
| WireGuard tunnel | Apache-2.0 ✓ سازگار |
| ZXing | Apache-2.0 ✓ |
| xray-core (فقط B) | **MPL-2.0 «Incompatible With Secondary Licenses»** |
| AndroidLibXrayLite (فقط B) | LGPL-3.0 |

چون ics-openvpn داخل اپ است، **کل اپ مشمول GPL می‌شود**. و شرطِ MPL اکسری از نظر فنی
با GPL ترکیب‌پذیر **نیست**.

> برای **استفاده‌ی شخصی روی گوشی خودت هیچ مسئله‌ای نیست** — این محدودیت فقط هنگام
> **توزیع** اهمیت دارد. برای Google Play هم علاوه بر این، سیاست VpnService گوگل فرم
> اعلام اجباری، افشای درون‌اپ و دو ویدیوی ≤۹۰ ثانیه می‌خواهد.

---

## ۸. اگر خواستی ادامه بدهی

### کارهای پیشنهادی که هنوز انجام نشده
| کار | سنگینی | یادداشت |
|---|---|---|
| تأیید Xray روی دستگاه واقعی | — | شاخه B هرگز کامل تست نشد؛ **اول این** |
| ادغام دو شاخه | متوسط | نقشه در فصل ۲ |
| مرتب‌سازی بر اساس تاخیر | سبک | فقط B، بعد از «تست همه» |
| ویجت صفحه‌ی خانه | متوسط | مثل تایل ولی روی هوم |
| اتصال خودکار هنگام بوت / وای‌فای خاص | متوسط | `RECEIVE_BOOT_COMPLETED` از قبل هست |
| روتینگ تقسیمی (per-app / geo) | سنگین | geo نیاز به برگرداندن فایل‌های ۲۷ مگابایتی دارد |
| کشور/آی‌پی حین اتصال | سنگین | تله ۴.۷ سرِ راه است |
| keystore پایدار برای release | سبک | هشدار فصل ۶ |

### روش کاری که در این پروژه جواب داد
۱. **هیچ emulator در دسترس نبود** (سندباکس KVM ندارد). هر تغییر با خواندن سورسِ واقعی
   کتابخانه‌ها و تست‌های JVM مستقل وارسی شد، نه با اجرا.
۲. **تست‌های JVM برای منطق خالص.** پارسر zip با ۷ آرشیو مخرب، منطق حذف با ۱۴ بررسی،
   قالب‌بندی اعداد با مقایسه‌ی عین‌به‌عین با موتور. این‌ها باگ واقعی گرفتند.
۳. **بازبینی مستقل کد بعد از هر قابلیت بزرگ.** بازبین چیزهایی پیدا کرد که خودم ندیدم —
   از جمله تله ۴.۵ که با symbolize کردن آدرس کرش در باینری اثبات شد.
۴. **وارسی APK با `aapt2` و `dexdump`** قبل از تحویل: مجوزها، سرویس‌ها، کتابخانه‌های
   نیتیو، و اینکه R8 چیزی را که نباید حذف نکرده باشد.

### نکته‌ای که به‌سختی یاد گرفتم
دو بار علت کرش را **حدس زدم** و اشتباه بود. آنچه جواب داد: آدرس کرش را در خودِ
`libgojni.so` symbolize کردن تا به نام تابع رسیدم. **اگر کرش native داری، اول
backtrace کامل را از `/data/tombstones/` یا `adb logcat` بگیر** — حدس زدن از روی کد
دو نسخه هدر داد.

---

## ۹. فایل‌های تحویلی

```
SwiftVPN-2p-v3.1.zip          سورس شاخه A (دو پروتکل) — آخرین
SwiftVPN-2p-v3.1-release.apk  APK امضاشده، ۳۱ مگابایت
SwiftVPN-v2.8.zip             سورس شاخه B (سه پروتکل) — آخرین
SwiftVPN-v2.8-release.apk     APK امضاشده، ۴۲ مگابایت
HANDOVER.md                   همین فایل
```

هر zip یک پروژه‌ی کامل و قابل build در Android Studio است (بدون `build/` و
`local.properties`). راهنمای اکسری داخل شاخه B در `docs/BUILDING_XRAY.md` است و
`README.md` هر شاخه توضیح معماری خودش را دارد.
