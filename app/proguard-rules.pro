# Preserve line numbers in stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JavaScript interfaces exposed to WebView must not be renamed or stripped
-keepclassmembers class com.example.hubengine.bluetooth.PrinterBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.example.hubengine.bluetooth.PrintBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ML Kit
-keep class com.google.mlkit.** { *; }

# CameraX
-keep class androidx.camera.** { *; }
