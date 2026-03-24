# Keep Nostr event serialization
-keep class com.narchives.reader.data.remote.nostr.** { *; }

# Keep NanoHTTPD
-keep class org.nanohttpd.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Readability4J
-keep class net.dankito.readability4j.** { *; }

# Keep Quartz
-keep class com.vitorpamplona.quartz.** { *; }
