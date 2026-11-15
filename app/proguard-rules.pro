# kotlinx.serialization ships keep rules for @Serializable classes; keep the
# cached data models whole anyway, since their JSON lives on the phone.
-keep class com.nwbort.gview.data.** { *; }

# Room creates each database's generated _Impl class reflectively through its
# no-argument constructor, which R8 full mode strips unless told otherwise
# (WorkManager's database, pulled in by Glance and the background sync).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
