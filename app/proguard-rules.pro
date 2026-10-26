# R8 keep rules for Lore's release build.
#
# Only two things in this app talk to native code across JNI, and both are the kind of thing R8
# breaks silently: it renames classes and strips the fields and methods it can prove are unused
# from Java, but it cannot see the C++ on the other side of the bridge, where those names are the
# lookup key. The result is not a compile error and not an R8 error — it is an
# UnsatisfiedLinkError or a NullPointerException on the phone, in an APK that looks completely
# healthy. Nothing here costs meaningfully in size: the classes are a few hundred methods.
#
# Verify a release build with app/build/outputs/mapping/release/mapping.txt open and
# `dexdump` on classes.dex: every kept class appears under its own name, and the shelf starts.

# ---------------------------------------------------------------- Google Filament (the shelf)

# Filament's Java classes are thin wrappers: every instance holds a raw pointer in a field the
# native side reads by name (`mNativeObject`), and every engine call is a `native` method resolved
# by its JNI name at run time. Renaming any of it — the class, the field, the method — takes the
# whole 3D shelf down before the first frame is drawn.
-keep class com.google.android.filament.** { *; }

# The runtime material compiler (filamat). Same bridge, and it is reached through reflection-free
# native calls that still depend on the class names above being intact.
-keep class com.google.android.filament.filamat.** { *; }

# Belt and braces for any class with a native method: whenever a `native` declaration survives
# R8, its name and signature must survive with it. This catches a dependency that ships JNI
# without its own consumer rules.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Filament ships no Java sources for these; R8 cannot always tell whether they are reachable and
# warns about them rather than failing. The binds above are what actually matter.
-dontwarn com.google.android.filament.**

# ---------------------------------------------------------------- Pdfium (the reader)

# The PDF reader binds to a native library the same way: `com.shockwave.pdfium.PdfiumCore` and
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

# Nothing to do here, and that is worth stating: the compiled shaders are read out of
# `assets/materials/` by file name, and the resource shrinker only removes `res/` entries, never
# assets. The materials step in CI is what guarantees those files exist; see
# .github/workflows/build-apk.yml.
