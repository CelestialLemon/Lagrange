# Keep kotlinx.serialization metadata for the backup manifest and preferences formats.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.celestiallemon.lagrange.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
