# Release builds of the sample: R8 full mode with the SDK's consumer rules.
# No logs in release: strip every android.util.Log call (the SDK itself never logs).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}
