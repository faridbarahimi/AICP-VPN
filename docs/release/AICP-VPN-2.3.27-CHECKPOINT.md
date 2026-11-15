# AICP VPN 2.3.27 — Release checkpoint

Date: 2026-11-15
Version: `2.3.27` (`versionCode=768`)
Package: `com.aicp.vpn`

## English — evidence and status

- **IMPLEMENTED:** dead-mirror 404/410 fail-fast; unified real-ping/bandwidth Smart Connect ranking; bounded Xray health recovery/backoff; user-facing Persian/English recovery and direct-network speed-test messages; refined Compose typography and compact home-card spacing.
- **TESTED:** 971/971 unit tests passed across 97 suites; 0 failures, errors or skips.
- **VERIFIED:** `lintRelease` passed (0 errors, 214 warnings, 1 hint); native runtime asset and Xray lock checks passed; all five APKs verified with APK Signature Scheme v2 and the official certificate; all five passed zipalign and package/version metadata checks.
- **ARTIFACTS:** five ABI APKs and SHA-256 inventory are in `release/`.
- **NOT VERIFIED / NOT OPERATIONAL:** no physical Android device was attached. Live VPN traffic, DNS/IPv6 leak behavior, real failover/reconnect timing, per-app routing and RTL Persian rendering still need device QA. Google Play publication is not claimed.
- **KNOWN LIMITATION:** selected new strings in Arabic, Russian, Vietnamese, Bengali, Chinese and BQI locales use English fallback pending proper translations.

## فارسی — شواهد و وضعیت

- **پیاده‌سازی شد:** توقف سریع روی خطاهای 404/410 منابع مرده؛ رتبه‌بندی Smart Connect با ترکیب پینگ واقعی و پهنای باند؛ بازیابی کنترل‌شدهٔ سلامت Xray با backoff؛ پیام‌های روشن فارسی/انگلیسی برای خطای بازیابی و رد تست سرعت در نبود شبکهٔ مستقیم تأییدشده؛ ظریف‌سازی و کوچک‌سازی محدود تایپوگرافی Compose و فشرده‌سازی کارت‌های صفحهٔ خانه.
- **تست شد:** هر ۹۷۱ تست واحد در ۹۷ مجموعه موفق؛ بدون شکست، خطا یا تست ردشده.
- **تأیید شد:** Lint نسخهٔ Release با صفر خطا (۲۱۴ هشدار و یک Hint)؛ بررسی دارایی‌های Native و قفل Xray موفق؛ امضای هر پنج APK با گواهی رسمی و APK Signature Scheme v2 تأیید شد؛ zipalign و مشخصات بسته/نسخه برای هر پنج فایل موفق بود.
- **خروجی‌ها:** پنج APK معماری‌محور و فایل SHA-256 در پوشهٔ `release/` قرار دارند.
- **تأییدنشده / عملیاتی نیست:** دستگاه اندرویدی فیزیکی متصل نبود. ترافیک واقعی VPN، نشت DNS/IPv6، زمان واقعی reconnect/failover، مسیریابی هر برنامه و نمایش RTL فارسی هنوز نیازمند تست روی دستگاه هستند. انتشار در Google Play ادعا نمی‌شود.
- **محدودیت شناخته‌شده:** برخی رشته‌های جدید در زبان‌های عربی، روسی، ویتنامی، بنگالی، چینی و بختیاری فعلاً با متن انگلیسی نمایش داده می‌شوند و ترجمهٔ اختصاصی آن‌ها باقی مانده است.

## Release identity

- Official release certificate SHA-256: `347d15f0992bb018a5ed2cfe9ed046daf17f6b132b53af4e8c194ec94608422a`
- Source release tag planned: `v2.3.27`
- Previous release `v2.3.26` remains preserved for history.
- Keystore and passwords remain outside the repository.
