# The current release build does not minify. Keep serialization names if minification is enabled later.
-keepattributes *Annotation*, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
