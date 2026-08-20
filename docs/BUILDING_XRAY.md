# ساختن دوباره‌ی `libv2ray.aar`

اپ برای Xray به `app/libs/libv2ray.aar` وابسته است. این فایل **در مخزن قرار دارد**
تا نصب معمولی به Go نیاز نداشته باشد؛ این سند فقط برای وقتی است که بخواهید آن را
به‌روزرسانی کنید (مثلاً برای نسخه‌ی جدیدتر xray-core).

بر خلاف OpenVPN و وایرگارد، Xray هیچ artifactِ منتشرشده در Maven ندارد — باید از
سورس با gomobile ساخته شود.

## چرا این معماری

- **wrapper:** [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
  (همان که v2rayNG استفاده می‌کند، LGPL-3.0). یک API تمیز روی xray-core می‌دهد:
  `newCoreController`، `startLoop(config, tunFd)`، `stopLoop`، `queryStats`.
- **بدون tun2socks جدا:** نسخه‌ی xray-core که این wrapper پین کرده، یک inbound از
  نوع `tun` با استک gvisor داخلی دارد (`proxy/tun/tun_android.go`) که مستقیم
  `xray.tun.fd` را می‌خواند. پس نه به `hev-socks5-tunnel` نیاز است نه build نیتیو
  دوم — فقط همین یک AAR.
- **حجم:** خروجی حدود ۱۹ مگابایت AAR است که داخلش `libgojni.so` برای arm64-v8a
  (حدود ۳۵ مگابایت، شامل کل xray-core + gvisor) قرار دارد.

## پیش‌نیازها

- Go (نسخه‌ی go.mod را ببینید؛ با 1.26.x ساخته شده)
- Android NDK (با 28.2.13676358 ساخته شده)
- Android SDK

## مراحل

```bash
# 1. Go را نصب کنید (اگر ندارید)
curl -sL https://go.dev/dl/go1.26.5.linux-amd64.tar.gz | tar -C /opt -xz
export GOROOT=/opt/go GOPATH=$HOME/gopath
export PATH=$GOROOT/bin:$GOPATH/bin:$PATH

# 2. wrapper را بگیرید
git clone --depth 1 https://github.com/2dust/AndroidLibXrayLite.git
cd AndroidLibXrayLite

# 3. gomobile را با نسخه‌ی پین‌شده‌ی go.mod نصب کنید
MOBVER=$(grep 'golang.org/x/mobile' go.mod | grep -v indirect | awk '{print $2}')
go install golang.org/x/mobile/cmd/gomobile@$MOBVER
go install golang.org/x/mobile/cmd/gobind@$MOBVER

export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
gomobile init

# 4. AAR را بسازید (فقط arm64، مطابق abiFilters اپ)
gomobile bind -target=android/arm64 -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' -o libv2ray.aar .

# 5. در پروژه بگذارید
cp libv2ray.aar /path/to/SwiftVPN2/app/libs/
```

`-checklinkname=0` مهم است: بعضی dependencyها از `//go:linkname` استفاده می‌کنند و
بدون این فلگ، لینکر Go 1.23+ خطا می‌دهد.

برای پشتیبانی از دستگاه‌های ۳۲ بیتی یا x86، به‌جای `android/arm64` از
`android/arm64,android/arm,android/amd64` استفاده کنید و `abiFilters` را در
`app/build.gradle.kts` هم‌راستا کنید (حجم APK زیاد می‌شود).

## بررسی API بعد از به‌روزرسانی

اگر AndroidLibXrayLite امضای متدهایش را عوض کند، این فایل‌ها باید تطبیق داده شوند:
- `app/src/main/java/ir/swiftvpn/xray/XrayVpnService.kt` — `newCoreController`،
  `startLoop(config, fd)`، `queryStats(tag, dir)`، `CoreCallbackHandler`
- `app/src/main/java/ir/swiftvpn/engine/xray/XrayConfig.kt` — شکل کانفیگ اگر
  schema اکسری عوض شد (مثلاً اسم counterهای stats یا فیلدهای inbound از نوع tun)
