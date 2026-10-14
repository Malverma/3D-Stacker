# libGDX uses reflection/JNI across its backend; keep it whole and let R8 shrink the rest.
-keep class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**
