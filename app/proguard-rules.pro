# The Anthropic SDK deserialises API responses reflectively through Jackson.
-keep class com.anthropic.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
