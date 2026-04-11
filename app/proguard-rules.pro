# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# OkHttp
-dontnote okhttp3.internal.platform.*
-dontnote okhttp3.internal.platform.ConscryptPlatform

# DataStore
-keep class * extends androidx.datastore.core.DataStore
-keep class * extends androidx.datastore.core.Serializer

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
