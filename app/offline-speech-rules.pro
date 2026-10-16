# sherpa-ncnn v2.1.15 uses JNI symbols and GetFieldID on these exact packages/members.
# Includes private native methods, ptr, config, and all nested config fields/constructors.
-keep class com.k2fsa.sherpa.ncnn.** { *; }
