# Release builds are shrunk and obfuscated by R8 (see app/build.gradle.kts).
# Compose, AndroidX lifecycle and Kotlin ship their own consumer rules; the app itself uses no
# reflection, so nothing of ours needs keeping beyond readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
