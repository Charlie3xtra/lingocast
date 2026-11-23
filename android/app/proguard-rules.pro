-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class com.lingocast.app.data.model.** { *; }
