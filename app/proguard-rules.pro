# Add project specific ProGuard rules here.
-keep class com.xvj.app.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
