# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Gemini API response classes from obfuscation
-keep class com.lagradost.quicknovel.util.GeminiResponse { *; }
-keep class com.lagradost.quicknovel.util.GeminiError { *; }
-keep class com.lagradost.quicknovel.util.GeminiCandidate { *; }
-keep class com.lagradost.quicknovel.util.GeminiContent { *; }
-keep class com.lagradost.quicknovel.util.GeminiPart { *; }

# Jackson rules for obfuscation
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,Annotation,EnclosingMethod,InnerClasses
-keepnames class com.fasterxml.jackson.** { *; }

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile