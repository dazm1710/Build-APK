# ProGuard rules for CamOBS

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keep class * extends dagger.hilt.android.HiltViewModel

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class *
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }

# Keep Media3
-keep class androidx.media3.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep SRT native library
-keep class com.camobs.streaming.SrtClient { *; }

# Keep ViewModel
-keep class com.camobs.ui.StreamViewModel { *; }

# Keep model classes
-keep class com.camobs.model.** { *; }

# Keep streaming engine
-keep class com.camobs.streaming.** { *; }

# Prevent obfuscation of JNI methods
-keepclassmembers class com.camobs.streaming.SrtClient {
    native <methods>;
}

# Keep Parcelable/Serializable
-keep class * implements android.os.Parcelable { *; }
-keep class * implements java.io.Serializable { *; }

# Keep R8/ProGuard annotations
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-dontwarn androidx.camera.**
-dontwarn androidx.media3.**