# ══════════════════════════════════════════════════════════════════════════════
# KIGHMU VPN — ProGuard / R8 Rules  (Anti-Reverse Engineering Edition)
# ══════════════════════════════════════════════════════════════════════════════

# ─── Optimisation R8 agressive ────────────────────────────────────────────────
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# ─── Repackaging complet dans un package opaque ───────────────────────────────
-repackageclasses 'k'
-flattenpackagehierarchy 'k'

# ─── Suppression des informations de débogage ─────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-keepattributes !LocalVariableTable,!LocalVariableTypeTable,!MethodParameters

# ─── Points d'entrée Android (ne jamais obfusquer) ────────────────────────────
-keep class com.kighmu.vpn.vpn.KighmuVpnService { *; }
-keep class com.kighmu.vpn.utils.BootReceiver { *; }
-keep class com.kighmu.vpn.ui.activities.MainActivity { *; }
-keep class com.kighmu.vpn.ui.activities.SplashActivity { *; }
-keep class com.kighmu.vpn.ui.activities.ImportActivity { *; }
-keep class com.kighmu.vpn.ui.activities.ExportActivity { *; }
-keep class com.kighmu.vpn.ui.activities.SettingsActivity { *; }
-keep class com.kighmu.vpn.ui.activities.LicenseActivity { *; }

# ─── Modèles JSON (Gson SerializedName doit rester intact) ────────────────────
-keep class com.kighmu.vpn.models.** { *; }
-keepclassmembers class com.kighmu.vpn.models.** { *; }

# ─── Config (chiffrement, validation) ─────────────────────────────────────────
-keep class com.kighmu.vpn.config.** { *; }
-keepclassmembers class com.kighmu.vpn.config.** { *; }

# ─── BuildConfig ──────────────────────────────────────────────────────────────
-keep class com.kighmu.vpn.BuildConfig { *; }

# ─── Méthodes JNI natives ─────────────────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ─── Sérialisation Parcelable / Serializable ──────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── Enum ─────────────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Gson ─────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ─── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── BouncyCastle ─────────────────────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ─── SSH (connectbot/sshlib + trilead) ────────────────────────────────────────
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ─── dnsjava (org.xbill.DNS) ──────────────────────────────────────────────────
# La classe sun.net.spi.nameservice.NameServiceDescriptor n'existe pas sur Android.
-dontwarn sun.net.spi.nameservice.**
# dnsjava utilise JNA pour certaines fonctions sur Windows, non applicable sur Android.
-dontwarn com.sun.jna.**
# dnsjava utilise JNDI pour certaines fonctions, non disponible sur Android.
-dontwarn javax.naming.**
# dnsjava contient des providers spécifiques à Windows.
-dontwarn org.xbill.DNS.spi.**
-dontwarn org.xbill.DNS.config.IPHlpAPI
-dontwarn org.xbill.DNS.config.WindowsResolverConfigProvider**
-dontwarn org.xbill.DNS.config.JndiContextResolverConfigProvider**
# dnsjava utilise Lombok pour générer du code, mais Lombok n'est pas une dépendance runtime.
-dontwarn lombok.**
# dnsjava utilise slf4j pour le logging, mais ce n'est pas une dépendance directe sur Android.
-dontwarn org.slf4j.**

-keep class org.xbill.DNS.** { *; }

# ─── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.**
-dontwarn kotlin.**

# ─── AndroidX / Material ──────────────────────────────────────────────────────
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ─── Suppression des logs en release (anti-fuite d'information) ───────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ─── Suppression des stack traces (anti-analyse d'erreur) ─────────────────────
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# ─── Divers avertissements à ignorer ──────────────────────────────────────────
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor
