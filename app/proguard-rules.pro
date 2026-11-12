# The native library registers NativeModem's natives by name and calls its
# callbacks (app/src/main/cpp/jni.cpp): keep them as they are.
-keep class org.dmodem.app.android.NativeModem {
    native <methods>;
    static void onLog(java.lang.String);
    static void onStatus(int, boolean, int, int);
    static void onExit(int);
}

# SSH: libdmodem_ssh_jni's JNI functions are named after NativeSsh's natives
-keep class org.dmodem.app.android.ssh.NativeSsh { native <methods>; }

# BouncyCastle finds its algorithms by name (JCA providers, factories) at
# run time: keep them whole.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn org.bouncycastle.**

# the local shell's pseudo-terminals: libdmodem_pty's JNI functions are named after these
-keep class org.dmodem.app.android.local.Pty { native <methods>; }
