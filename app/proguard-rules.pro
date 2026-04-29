# R8 / ProGuard rules for blemesh-router.
#
# This app has no reflection, no serialization framework, no Jackson/Gson/Moshi.
# All entry points are declared in the manifest (Activity, Service, Receiver),
# which AGP keeps automatically. Default `proguard-android-optimize.txt` rules
# are sufficient.
#
# If a release build crashes with NoClassDefFoundError or "no such method",
# add a -keep rule here for the offending class.

# Keep Kotlin metadata so reflection-driven libraries (if added later) work.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Preserve line numbers in stack traces; ship the resulting mapping.txt with
# the release for de-obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
