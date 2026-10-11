# m.jafari ows

اپلیکیشن اندروید برای تکمیل خودکار فرم‌های سامانه OWS

---

## نصب و راه‌اندازی

### ۱. باز کردن پروژه در Android Studio

1. Android Studio را باز کنید
2. **File → Open** را انتخاب کنید
3. پوشه `mjafari-ows` را انتخاب کنید
4. منتظر بمانید تا Gradle sync انجام شود

### ۲. ساخت APK

روش اول (دستی):
- **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- فایل APK در `app/build/outputs/apk/debug/` قرار می‌گیرد

روش دوم (نصب مستقیم):
- گوشی را با USB وصل کنید
- **Run → Run 'app'** را کلیک کنید

### ۳. فعال‌سازی Accessibility Service

1. تنظیمات گوشی را باز کنید
2. به **Accessibility** بروید
3. **Downloaded Apps** یا **Installed Services** را پیدا کنید
4. **m.jafari ows** را پیدا و فعال کنید
5. تأیید کنید

---

## نحوه استفاده

1. سامانه OWS را در مرورگر یا اپ مربوطه باز کنید
2. به فرم موردنظر بروید
3. اپ **m.jafari ows** را باز کنید
4. حالت **OK** یا **NOT OK** را انتخاب کنید
5. دکمه **START** را فشار دهید
6. برنامه:
   - فیلدهای dropdown را شناسایی و مقدار مناسب انتخاب می‌کند
   - فیلدهای متنی را شناسایی و مقدار تایپ می‌کند
   - به‌صورت خودکار اسکرول می‌کند
   - فیلدهای ناشناخته را برای بررسی دستی علامت می‌زند
7. پس از پایان، پیام تکمیل نمایش داده می‌شود
8. **Submit را خودتان فشار دهید** — برنامه هرگز آن را نمی‌فشارد

---

## دکمه‌ها

| دکمه | عملکرد |
|------|---------|
| START | شروع عملیات خودکار |
| STOP | توقف فوری |
| PAUSE | توقف موقت |
| RESUME | ادامه از همان جا |
| ACCESSIBILITY SETTINGS | باز کردن تنظیمات Accessibility |
| CLEAR LOG | پاک کردن لاگ |

---

## هشدار مهم

⚠ این برنامه **هرگز** دکمه Submit را فشار نمی‌دهد.
⚠ پیش از ارسال فرم، همه اطلاعات را بررسی کنید.

---

## ساختار پروژه

```
app/src/main/java/com/mjafari/ows/
├── MainActivity.kt          — رابط کاربری
├── OWSAccessibilityService.kt — سرویس اصلی
├── OWSFieldDetector.kt      — تشخیص فیلدها
├── OWSFormProcessor.kt      — پردازش فیلدها
├── OWSScrollManager.kt      — مدیریت اسکرول
├── OWSStateManager.kt       — مدیریت وضعیت
└── OWSLogger.kt             — ثبت لاگ
```

---

## نیازمندی‌ها

- Android 7.0 (API 24) یا بالاتر
- Samsung Galaxy A05 (Android 13 — کاملاً سازگار)
- بدون نیاز به Root
