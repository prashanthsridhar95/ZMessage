# Release builds are shrunk and obfuscated by R8 (see app/build.gradle.kts).
# Compose, AndroidX lifecycle and Kotlin ship their own consumer rules; the app itself uses no
# reflection, so nothing of ours needs keeping beyond readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reflection audit (release is shrunk with R8; keep this list short and justified):
#  - org.json is the platform's own copy, never shrunk.
#  - Services, receivers, providers and the Application are kept by the rules AGP generates from the manifest.
#  - There are no Parcelable or Serializable classes of our own and no Class.forName / ServiceLoader use.
#  - Enums are saved by name (SharedPreferences, backups) and read back with valueOf. R8 never renames an enum
#    constant's name(), but this keeps values()/valueOf() for the domain enums even if a later R8 inlines them.
-keepclassmembers enum com.zyke.message.domain.**, com.zyke.message.data.backup.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
