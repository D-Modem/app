# The native library registers NativeModem's natives by name and calls its
# callbacks (app/src/main/cpp/jni.cpp): keep them as they are.
-keep class com.neodesktop.dmodem.NativeModem {
    native <methods>;
    static void onLog(java.lang.String);
    static void onStatus(int, boolean, int, int);
    static void onExit(int);
}
