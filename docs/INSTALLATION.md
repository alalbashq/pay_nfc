# التثبيت والبناء

ثبّت Node.js 22 وJDK 17 وGradle 8.9 وAndroid SDK Platform 35 وأدوات Platform Tools. اضبط `ANDROID_HOME` أو حدّد `sdk.dir` في ملف `local.properties` داخل جذر المشروع. يتطلب أول بناء وصولًا إلى npm وGoogle Maven وMaven Central وGradle Plugin Portal.

## Linux

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
npm ci
gradle :core:testDebugUnitTest :pos:lintDebug :pay:lintDebug :pos:assembleDebug :pay:assembleDebug
adb devices
adb -s POS_SERIAL install -r apps/ghallah-pos/android/build/outputs/apk/debug/*-debug.apk
adb -s PAY_SERIAL install -r apps/ghallah-pay/android/build/outputs/apk/debug/*-debug.apk
```

## Windows PowerShell

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path += ";$env:ANDROID_HOME\platform-tools"
npm.cmd ci
gradle.bat :core:testDebugUnitTest :pos:lintDebug :pay:lintDebug :pos:assembleDebug :pay:assembleDebug
adb devices
$posApk = (Get-ChildItem .\apps\ghallah-pos\android\build\outputs\apk\debug\*-debug.apk).FullName
adb -s POS_SERIAL install -r $posApk
$payApk = (Get-ChildItem .\apps\ghallah-pay\android\build\outputs\apk\debug\*-debug.apk).FullName
adb -s PAY_SERIAL install -r $payApk
```

يمكن استيراد المشروع من جذره في Android Studio أيضًا. Gradle يوقّع نسخ Debug بمفتاح التطوير المحلي. الحد الأدنى API 26. يلزم NFC للقارئ وNFC مع HCE للمستقبل. استخدم جهازين بشاشتين مفتوحتين أثناء اختبار التجربة الأساسية.
