# kotlinx.serialization ships keep rules for @Serializable classes; keep the
# cached data models whole anyway, since their JSON lives on the phone.
-keep class com.nwbort.gview.data.** { *; }

# Room creates each database's generated _Impl class reflectively through its
# no-argument constructor, which R8 full mode strips unless told otherwise
# (WorkManager's database, pulled in by Glance and the background sync).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit's on-device GenAI (Gemini Nano via AICore) ships almost no keep rules
# and failed with a bare NullPointerException on every call in the minified
# release build. Keep it whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
