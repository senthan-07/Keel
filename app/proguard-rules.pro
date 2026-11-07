# SQLCipher reaches these classes from native code, so their names must survive shrinking.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
