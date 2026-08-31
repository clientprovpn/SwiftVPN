# Engine classes are reached across the process/AIDL boundary.
-keep class de.blinkt.openvpn.** { *; }
-keep class net.openvpn.ovpn3.** { *; }

# gomobile bindings for Xray. libgojni.so calls into these Java classes by name
# through JNI (go.Seq's reflection-based bridge), so R8 must not rename or strip
# them or the core fails to start with a NoSuchMethod/UnsatisfiedLink error.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keepclassmembers class libv2ray.** { *; }
# Our callback handler is invoked from native code.
-keep class ir.swiftvpn.xray.** { *; }

# WireGuard's GoBackend loads libwg-go.so and bridges to it by JNI; keep the
# library surface so R8 does not rename the classes the native side looks up.
-keep class org.amnezia.awg.** { *; }
# strongSwan IKEv2: charon calls back into these classes by exact JNI name
-keep class org.strongswan.** { *; }


# Compile-time-only JSR-305 annotations referenced by the WireGuard library.
# They are not on the Android classpath; the references are harmless.
-dontwarn javax.annotation.Nonnull
-dontwarn javax.annotation.meta.TypeQualifierDefault
