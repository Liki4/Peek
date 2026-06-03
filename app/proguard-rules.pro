# Nordic BLE — keep callbacks the library invokes reflectively
-keep class no.nordicsemi.android.ble.** { *; }
-dontwarn no.nordicsemi.android.ble.**

# Garmin FIT SDK
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# Keep our protocol enums (used in serialization and Compose state)
-keep enum io.github.liki4.peek.protocol.** { *; }

# Room: entities are inspected reflectively for column<->property mapping.
# (Room ships its own consumer rules but we keep our own entities explicitly
# so a future obfuscation rule won't mangle them.)
-keep class io.github.liki4.peek.history.RideSessionEntity { *; }
