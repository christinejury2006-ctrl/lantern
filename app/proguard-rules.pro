# R8 keep rules for Lore's release build.
#
# Only one thing in this app talks to native code across JNI, and it is the kind of thing R8 breaks
# silently: it renames classes and strips the fields and methods it can prove are unused from
# Java, but it cannot see the C++ on the other side of the bridge, where those names are the
# lookup key. The result is not a compile error and not an R8 error — it is an
# UnsatisfiedLinkError or a NullPointerException on the phone, in an APK that looks completely
# healthy. Nothing below costs meaningfully in size.
#
# Verify a release build with app/build/outputs/mapping/release/mapping.txt open and
# `dexdump` on classes.dex: every kept class appears under its own name, and a PDF still opens.

# ---------------------------------------------------------------- Pdfium (the reader)

# The PDF reader binds to a native library: `com.shockwave.pdfium.PdfiumCore` and
# its documents are name-resolved from C++, and their fields are read by the native side. Stripping
# or renaming them breaks opening a PDF, while the rest of the app still works, so the failure
# looks like a corrupt file rather than a build problem.
-keep class com.shockwave.pdfium.** { *; }
-keepclasseswithmembers,includedescriptorclasses class com.shockwave.pdfium.** { *; }
-dontwarn com.shockwave.pdfium.**

# ---------------------------------------------------------------- Kotlin and Compose

# Kotlin's own metadata is not needed at run time, but `kotlin.Metadata` is read by tooling and by
# reflection in a couple of Compose paths; keeping the attribute costs nothing and removes a class
# of "works in debug, crashes in release" reports.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# Keep line numbers so a release crash still names a line in the source. Debug builds are not
# obfuscated, so this only affects release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- Assets read by name

# Nothing to do here, and that is worth stating: the starter books are read out of
# `assets/starter/` by file name, and the resource shrinker only removes `res/` entries, never
# assets.
