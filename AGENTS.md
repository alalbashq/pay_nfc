# Ghallah Payment

حافظ على سلامة المبالغ باستخدام وحدات العملة الصغرى. خزّن قرار الدفع قبل إرساله واحفظ معرّف المعاملة عند إعادة الاتصال. أي تعديل سلكي على GNPP يتطلب تحديث وثيقة البروتوكول واختبار توافق Reader وHCE.

استخدم Gradle 8.9 مع JDK 17 وAndroid SDK 35. شغّل `gradle :core:testDebugUnitTest :pos:lintDebug :pay:lintDebug :pos:assembleDebug :pay:assembleDebug` قبل تسليم إصدارات Android. سجّل نتيجة الأجهزة الفعلية مستقلة عن الاختبارات البرمجية.
