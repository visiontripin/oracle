# Keep DTOs (Gson) and JS bridge
-keep class com.botcontrol.admin.data.remote.** { *; }
-keep class com.botcontrol.admin.data.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.google.gson.**
