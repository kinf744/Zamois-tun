package com.kighmu.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SessionManager
import com.kighmu.vpn.models.SshCredentials
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kighmu.vpn.R
import com.kighmu.vpn.config.ConfigManager
import com.kighmu.vpn.config.ConfigEncryption
import com.kighmu.vpn.engines.SlowDnsEngine
import com.kighmu.vpn.engines.TunnelEngine
import com.kighmu.vpn.engines.TunnelEngineFactory
import com.kighmu.vpn.models.*
import com.kighmu.vpn.ui.activities.MainActivity
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class KighmuVpnService : VpnService() {

    companion object {
        const val TAG = "KighmuVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "kighmu_vpn_channel"
        const val ACTION_START = "com.kighmu.vpn.START"
        const val ACTION_STOP = "com.kighmu.vpn.STOP"
        const val ACTION_RECONNECT = "com.kighmu.vpn.RECONNECT"
        const val BROADCAST_STATUS = "com.kighmu.vpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        var instance: KighmuVpnService? = null
        var currentStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        var stats = VpnStats()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelEngine: TunnelEngine? = null
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var configManager: ConfigManager
    private var currentConfig: KighmuConfig = KighmuConfig()
    private var reconnectAttempts = 0
    private val MAX_RECONNECT = 20
    private var userRequestedStop = false
    private var currentProfileIndex = 0
    private var sessionManager: SessionManager? = null
    private val RECONNECT_DELAY = 5000L
    private val maxReconnectAttempts = 5
    private var statsJob: Job? = null
    private var vpnJob: Job? = null
    private var tun2socksRelay: Tun2SocksRelay? = null
    private var zivpnServerIp: String? = null
    @Volatile var underlyingNetwork: android.net.Network? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        KighmuLogger.info(TAG, "Service démarré")
        super.onCreate()
        instance = this
        configManager = ConfigManager(this)
        registerNetworkCallback()
        createNotificationChannel()
        // Capturer et sauvegarder les crashes dans un fichier
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_crash.txt")
                val sb = StringBuilder()
                sb.appendLine("====== KIGHMU VPN CRASH REPORT ======")
                sb.appendLine("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                sb.appendLine("Thread: ${thread.name} (id=${thread.id})")
                sb.appendLine("======================================")
                sb.appendLine()
                sb.appendLine("--- EXCEPTION ---")
                sb.appendLine("Type: ${throwable.javaClass.name}")
                sb.appendLine("Message: ${throwable.message}")
                sb.appendLine()
                sb.appendLine("--- CAUSE ---")
                var cause = throwable.cause
                while (cause != null) {
                    sb.appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                }
                sb.appendLine()
                sb.appendLine("--- STACK TRACE ---")
                throwable.stackTrace.forEach { sb.appendLine("  at $it") }
                sb.appendLine()
                sb.appendLine("--- DEVICE INFO ---")
                sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                sb.appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                sb.appendLine()
                sb.appendLine("--- NATIVE LIBS ---")
                try {
                    val nativeDir = applicationInfo.nativeLibraryDir
                    sb.appendLine("NativeLibDir: $nativeDir")
                    java.io.File(nativeDir).listFiles()?.forEach {
                        sb.appendLine("  ${it.name} (${it.length()} bytes)")
                    }
                } catch (e: Exception) { sb.appendLine("Error: ${e.message}") }
                sb.appendLine()
                sb.appendLine("--- VPN STATE ---")
                sb.appendLine("TunnelMode: ${currentConfig.tunnelMode.name}")
                sb.appendLine("======================================")
                crashFile.writeText(sb.toString())
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        
        startForeground(NOTIFICATION_ID, buildNotification("Connecting"))
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> reconnect()
            null -> if (!userRequestedStop) startVpn()
        }
        return if (userRequestedStop) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        KighmuLogger.warning(TAG, "VPN révoqué par le système")
        
        // Android révoque le VPN - on reconnecte immédiatement
        KighmuLogger.info(TAG, "VPN révoqué par Android - reconnexion...")
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        val autoReconnect = getSharedPreferences("kighmu_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("auto_reconnect", true)
        if (!userRequestedStop && autoReconnect) {
            serviceScope.launch {
                    isStartingVpn = false  // reset guard
                    delay(300)  // 1000ms -> 300ms
                startVpn()
            }
        } else if (!userRequestedStop && !autoReconnect) {
            KighmuLogger.info(TAG, "Auto-reconnect désactivé - VPN arrêté définitivement")
            updateStatus(ConnectionStatus.DISCONNECTED, "Auto-reconnect désactivé")
        }
    }

    override fun onDestroy() {
        KighmuLogger.info(TAG, "Service arrêté")
        
        
        
        vpnJob?.cancel()
        statsJob?.cancel()
        serviceJob.cancel()
        unregisterNetworkCallback()
        tunnelEngine = null
        // Fermer interface TUN - clé VPN disparaît ICI
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {
            try { @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) {}
        }
        updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
        super.onDestroy()
    }

    private var isStartingVpn = false
    private fun startVpn() {
        if (isStartingVpn) return
        isStartingVpn = true
        userRequestedStop = false
        // Acquérir WakeLock pour empêcher Android de tuer le service
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "KighmuVPN::WakeLock"
            )
            wakeLock?.acquire(8 * 60 * 60 * 1000L) // Max 8 heures
        }
        if (reconnectAttempts == 0) currentProfileIndex = 0
        vpnJob = serviceScope.launch {
            try {
                updateStatus(ConnectionStatus.CONNECTING, "Loading configuration...")
                currentConfig = configManager.loadCurrentConfig()

                val validationResult = ConfigEncryption.validateConfig(this@KighmuVpnService, currentConfig)
                when (validationResult) {
                    is ConfigEncryption.ValidationResult.Expired -> {
                        
                        updateStatus(ConnectionStatus.ERROR, "Config expired")
                        return@launch
                    }
                    is ConfigEncryption.ValidationResult.WrongDevice -> {
                        updateStatus(ConnectionStatus.ERROR, "Config locked to another device")
                        return@launch
                    }
                    else -> {}
                }

                updateStatus(ConnectionStatus.CONNECTING, "Starting tunnel engine...")
                startForeground(NOTIFICATION_ID, buildNotification("Connecting"))

                // Surveillance periodique expiration + hardware ID (toutes les 60s)
                serviceScope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(60_000L)
                        val cfg = currentConfig ?: break
                        // Verifier expiration
                        if (cfg.expiresAt > 0L && System.currentTimeMillis() > cfg.expiresAt) {
                            updateStatus(ConnectionStatus.ERROR, "Config expired")
                            stopVpn()
                            break
                        }
                        // Verifier hardware ID
                        if (cfg.hardwareId.isNotEmpty()) {
                            val currentHwId = com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this@KighmuVpnService)
                            if (cfg.hardwareId != currentHwId) {
                                updateStatus(ConnectionStatus.ERROR, "Config locked to another device")
                                stopVpn()
                                break
                            }
                        }
                        // Verifier via ExportConfig aussi
                        val sec = cfg.exportConfig
                        if (sec != null) {
                            if (sec.expiresAt > 0L && System.currentTimeMillis() > sec.expiresAt) {
                                updateStatus(ConnectionStatus.ERROR, "Config expired")
                                stopVpn()
                                break
                            }
                            if (sec.lockDeviceId && sec.hardwareId.isNotEmpty()) {
                                val currentHwId = com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this@KighmuVpnService)
                                if (sec.hardwareId != currentHwId) {
                                    updateStatus(ConnectionStatus.ERROR, "Config locked to another device")
                                    stopVpn()
                                    break
                                }
                            }
                        }
                    }
                }

                val tempVpn = try {
                    Builder()
                        .setSession("KIGHMU VPN")
                        .addAddress("10.0.0.2", 24)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("8.8.8.8")
                        .setMtu(1400)
                        .addDisallowedApplication(packageName)
                        .establish()
                } catch (e: Exception) {
                    KighmuLogger.warning("VpnService", "TempVPN: ${e.message}")
                    null
                }
                                


                // Pour ZIVPN: creer interface VPN AVANT engine.start() pour eviter boucle UDP
                if (currentConfig.tunnelMode == TunnelMode.ZIVPN_UDP) {
                    val host = currentConfig.zivpnHost.trim()
                    if (host.isNotEmpty()) {
                        try {
                            val resolved = java.net.InetAddress.getByName(host).hostAddress
                            if (resolved != null && resolved.isNotEmpty()) {
                                zivpnServerIp = resolved
                                
                            }
                        } catch (e: Exception) {
                            
                        }
                    }
                    try { tempVpn?.close() } catch (_: Exception) {}
                    try { vpnInterface?.close() } catch (_: Exception) {}
                    vpnInterface = null
                    
                    vpnInterface = buildVpnInterface(0)
                    
                }

                val localPort = try {
                    
                // Injecter le profil V2DNS sélectionné dans la config avant démarrage
                // Mode 5 (V2RAY_SLOWDNS): XrayDnsEngineFactory gere les profils directement
                // Mode 4 (V2RAY_XRAY): XrayVpnEngineFactory gere les profils directement
                // Aucune injection manuelle necessaire - chaque engine lit son propre repo
                tunnelEngine = TunnelEngineFactory.create(currentConfig, this@KighmuVpnService, this@KighmuVpnService)
                
                    
                tunnelEngine!!.start()
                } catch (e: Exception) {
                    KighmuLogger.error("VpnService", "Engine failed: ${e.javaClass.simpleName}: ${e.message}")
                    try { tempVpn?.close() } catch (_: Exception) {}
                    if (!userRequestedStop && reconnectAttempts < MAX_RECONNECT) {
                        reconnectAttempts++
                        isStartingVpn = false // Reset pour permettre la reconnexion
                        
                        updateStatus(ConnectionStatus.CONNECTING, "Reconnecting... ($reconnectAttempts/$MAX_RECONNECT)")
                        delay(RECONNECT_DELAY)
                        startVpn()
                    } else {
                        reconnectAttempts = 0
                        
                        updateStatus(ConnectionStatus.ERROR, "Echec apres $MAX_RECONNECT tentatives")
                    }
                    isStartingVpn = false
                    return@launch
                }
                
                try { tempVpn?.close() } catch (_: Exception) {}

                // Fermer interface VPN precedente (evite tun1, tun2...)
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null

                updateStatus(ConnectionStatus.CONNECTING, "Creating VPN interface...")

                
                // Pour ZIVPN: interface deja creee avant engine.start()
                if (currentConfig.tunnelMode != TunnelMode.ZIVPN_UDP || vpnInterface == null) {
                    vpnInterface = buildVpnInterface(localPort)
                } else {
                    
                }
                
                if (vpnInterface == null) {
                    KighmuLogger.error(TAG, "Interface VPN indisponible")
                    updateStatus(ConnectionStatus.ERROR, "Failed to create VPN interface")
                    try { tunnelEngine?.stop() } catch (_: Exception) {}
                    return@launch
                }

                // Routing via tun2socks JNI (arm64) ou Kotlin relay (fallback)
                // Garder vpnInterface ouvert - le fermer au stop libère la clé VPN
                                
                tunnelEngine?.startTun2Socks(vpnInterface!!.fd)
                
                
                reconnectAttempts = 0
                stats = VpnStats(connectedAt = System.currentTimeMillis())
                isStartingVpn = false
                updateStatus(ConnectionStatus.CONNECTED, "Connected")
                updateNotification("Connected")
                startStatsUpdate()
                startWatchdog()

            } catch (e: Exception) {
                
                
                KighmuLogger.error(TAG, "Erreur démarrage: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "VPN start error", e)
                updateStatus(ConnectionStatus.ERROR, e.message ?: "Connection failed")
            }
        }
    }

                        private fun stopVpn() {
        userRequestedStop = true
        isStartingVpn = false  // ← CRITIQUE : débloque le guard immédiatement
                
        // 1. Libérer WakeLock immédiatement
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        reconnectAttempts = 0

        // 2. Annuler vpnJob EN PREMIER → interrompt engine.start() en cours
        vpnJob?.cancel()
        statsJob?.cancel()

        val engineRef = tunnelEngine
        tunnelEngine = null
        tun2socksRelay = null
        stats = VpnStats()

        // 3. Fermer l'interface VPN IMMÉDIATEMENT (pas besoin d'attendre l'engine)
        //    C'est ça qui libère la clé VPN côté Android → UI réagit instantanément
        try {
            vpnInterface?.close()
                    } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur fermeture interface: ${e.message}")
        }
        vpnInterface = null

        // 4. Signaler STOPPING pour ZIVPN (bloque le bouton Connect pendant nettoyage)
        //    ou DISCONNECTED immédiatement pour les autres tunnels
        if (currentConfig.tunnelMode == com.kighmu.vpn.models.TunnelMode.ZIVPN_UDP) {
            updateStatus(ConnectionStatus.STOPPING, "Stopping...")
        } else {
            updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
        }

        // 5. Retirer notification immédiatement
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            try { @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) {}
        }

        // 6. Nettoyage engine + processus natifs EN ARRIÈRE-PLAN
        val isZivpn = currentConfig.tunnelMode == com.kighmu.vpn.models.TunnelMode.ZIVPN_UDP
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (isZivpn) {
                    // ZIVPN: arrêt SYNCHRONE - tuer tout avant de signaler DISCONNECTED
                    withTimeoutOrNull(4000L) { engineRef?.stop() }
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                            "killall -9 libuz_core.so libxray.so 2>/dev/null; " +
                            "pkill -9 -f libuz_core 2>/dev/null; " +
                            "sleep 1"
                        ))
                        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                    zivpnServerIp = null
                    tunnelEngine = null
                    // Signaler DISCONNECTED seulement après nettoyage complet
                    withContext(Dispatchers.Main) {
                        updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
                    }
                    KighmuLogger.info(TAG, "ZIVPN arret nucleaire complet - pret pour reconnexion")
                    // NE PAS appeler stopSelf() - garder service vivant pour reconnexion rapide
                } else {
                    withTimeoutOrNull(3000L) { engineRef?.stop() }
                    try { Runtime.getRuntime().exec(arrayOf("sh", "-c",
                        "killall -9 libtun2socks.so xray hysteria libhysteria.so dnstt libdnstt.so libuz_core.so"
                    )) } catch (_: Exception) {}
                    try { Runtime.getRuntime().exec(arrayOf("sh", "-c",
                        "pkill -9 -f dnstt"
                    )) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
                }
            }
        }
    }
    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    underlyingNetwork = network
                    
                    KighmuLogger.info(TAG, "Réseau disponible → vérification état")
                    if (!userRequestedStop) {
                        serviceScope.launch {
                            delay(1500) // laisser le réseau se stabiliser
                            if (!userRequestedStop &&
                                currentStatus != ConnectionStatus.CONNECTING &&
                                currentStatus != ConnectionStatus.CONNECTED) {
                                KighmuLogger.info(TAG, "Réseau rétabli → reconnexion")
                                reconnect()
                            }
                        }
                    }
                }
                override fun onLost(network: Network) {
                    underlyingNetwork = null
                    
                    KighmuLogger.info(TAG, "Réseau perdu → attente rétablissement")
                    if (!userRequestedStop && currentStatus == ConnectionStatus.CONNECTED) {
                        updateStatus(ConnectionStatus.DISCONNECTED, "Réseau perdu")
                    }
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
                    } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur registerNetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
            connectivityManager = null
        } catch (_: Exception) {}
    }

    private fun reconnect() {
        
        serviceScope.launch {
            try { tunnelEngine?.stop() } catch (_: Exception) {}
            tunnelEngine = null
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            isStartingVpn = false
            userRequestedStop = false
            // Attendre libération complète des ports avant redémarrage
            delay(1500)
            startVpn()
        }
    }

    private fun addSplitRoutes(builder: Builder, excludeIp: String) {
        // Genere les blocs CIDR couvrant 0.0.0.0/0 sauf excludeIp/32
        // Maximum ~64 routes au lieu de milliers
        try {
            val parts = excludeIp.split(".").map { it.toInt() }
            val excl = ((parts[0].toLong() shl 24) or (parts[1].toLong() shl 16) or
                       (parts[2].toLong() shl 8) or parts[3].toLong()) and 0xFFFFFFFFL

            fun addBlocks(start: Long, end: Long) {
                var cur = start
                while (cur <= end) {
                    var bits = 32
                    while (bits > 0) {
                        val mask = (0xFFFFFFFFL shl (32 - bits + 1)) and 0xFFFFFFFFL
                        if ((cur and mask) == cur && cur + (1L shl (32 - bits + 1)) - 1 <= end) bits--
                        else break
                    }
                    val ip = "%d.%d.%d.%d".format(
                        (cur shr 24) and 0xFF, (cur shr 16) and 0xFF,
                        (cur shr 8) and 0xFF, cur and 0xFF)
                    builder.addRoute(ip, bits)
                    cur += 1L shl (32 - bits)
                }
            }

            if (excl > 0L) addBlocks(0L, excl - 1L)
            if (excl < 0xFFFFFFFFL) addBlocks(excl + 1L, 0xFFFFFFFFL)
            KighmuLogger.info(TAG, "Split routes OK, exclu: $excludeIp/32")
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "addSplitRoutes erreur: ${e.message}")
            builder.addRoute("0.0.0.0", 0)
        }
    }


    private fun buildVpnInterface(@Suppress("UNUSED_PARAMETER") localProxyPort: Int): ParcelFileDescriptor? {
        
        val prefs = getSharedPreferences("kighmu_prefs", android.content.Context.MODE_PRIVATE)

        // Lire les settings utilisateur
        val mtuValue = prefs.getString("mtu", "1500")?.toIntOrNull()?.coerceIn(576, 9000) ?: 1500
        val dnsProtection = prefs.getBoolean("dns_protection", true)
        val dnsPrimary = prefs.getString("dns_primary", "")?.trim() ?: ""
        val dnsSecondary = prefs.getString("dns_secondary", "")?.trim() ?: ""
        val killSwitch = prefs.getBoolean("kill_switch", false)
        val wakelock = prefs.getBoolean("wakelock", true)

        // WakeLock selon setting
        if (wakelock && (wakeLock == null || wakeLock?.isHeld == false)) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KighmuVPN::WakeLock")
            wakeLock?.acquire(8 * 60 * 60 * 1000L)
                    } else if (!wakelock && wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
                    }

        return try {
            // Pour ZIVPN Bridge: adresse TUN differente (gvisor)
            val isZivpn = currentConfig.tunnelMode == TunnelMode.ZIVPN_UDP
            val tunAddress = if (isZivpn) "172.19.0.1" else "10.0.0.2"
            val tunPrefix  = if (isZivpn) 30 else 24
            
            
            val builder = Builder()
                .setSession("KIGHMU VPN")
                .addAddress(tunAddress, tunPrefix)
                .setMtu(mtuValue)
                .apply { if (isZivpn) allowBypass() }
                .setBlocking(true)

            // Routes: exclure IP serveur ZIVPN pour eviter boucle UDP
            val serverIp = zivpnServerIp
            if (currentConfig.tunnelMode == TunnelMode.ZIVPN_UDP && !serverIp.isNullOrEmpty()) {
                addSplitRoutes(builder, serverIp)
                KighmuLogger.info(TAG, "ZIVPN: routes split, IP exclue: $serverIp")
                
            } else {
                builder.addRoute("0.0.0.0", 0)
                
                
            }

            // DNS selon settings
            if (dnsProtection) {
                if (dnsPrimary.isNotBlank()) builder.addDnsServer(dnsPrimary)
                    else builder.addDnsServer("8.8.4.4")
                if (dnsSecondary.isNotBlank()) builder.addDnsServer(dnsSecondary)
                    else builder.addDnsServer("8.8.8.8")
                KighmuLogger.info(TAG, "DNS protection: ${if (dnsPrimary.isNotBlank()) dnsPrimary else "8.8.4.4"} / ${if (dnsSecondary.isNotBlank()) dnsSecondary else "8.8.8.8"}")
            } else {
                builder.addDnsServer("8.8.8.8")
                KighmuLogger.info(TAG, "DNS protection désactivée - DNS par défaut")
            }

            // Kill Switch - bloquer tout trafic hors VPN
            if (killSwitch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setBlocking(true)
                KighmuLogger.info(TAG, "Kill Switch activé - trafic bloqué hors VPN")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            KighmuLogger.info(TAG, "VPN Interface: MTU=$mtuValue, KillSwitch=$killSwitch, DNS=$dnsProtection")
            builder.addDisallowedApplication(packageName)
            // Exclure aussi le processus UI du VPN pour les opérations réseau internes
            // (export cloud, import, vérifications) - évite les timeouts quand VPN actif
            try { builder.addDisallowedApplication("$packageName:ui") } catch (_: Exception) {}
            val vpnFd = builder.establish()
            
            vpnFd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    private fun startSocks5Routing(vpnFd: ParcelFileDescriptor, socksPort: Int) {
                tun2socksRelay = Tun2SocksRelay(vpnFd.fileDescriptor, "127.0.0.1", socksPort)
        tun2socksRelay?.start()
            }

    private fun getTunIfaceName(): String? {
        return try {
            val fd = vpnInterface?.fd ?: return null
            val link = java.io.File("/proc/self/fd/$fd").canonicalPath
            // link ressemble a /dev/tun5 ou /dev/vpn0
            link.substringAfterLast("/").ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun readTunStats(): Pair<Long, Long> {
        return try {
            val rx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val tx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            Pair(rx, tx)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    private fun startStatsUpdate() {
        statsJob = serviceScope.launch {
            val ifaceName = getTunIfaceName()
            val initialStats = readTunStats()
            var baseDown = initialStats.first
            var baseUp = initialStats.second
            var lastDown = 0L
            var lastUp = 0L
            var pingCounter = 0
            while (isActive) {
                delay(1000)
                val tunStats = readTunStats()
                stats.downloadBytes = (tunStats.first - baseDown).coerceAtLeast(0L)
                stats.uploadBytes = (tunStats.second - baseUp).coerceAtLeast(0L)
                stats.downloadSpeed = (stats.downloadBytes - lastDown).coerceAtLeast(0L)
                stats.uploadSpeed = (stats.uploadBytes - lastUp).coerceAtLeast(0L)
                
                lastUp = stats.uploadBytes
                lastDown = stats.downloadBytes
                // Ping toutes les 30 secondes seulement
                pingCounter++
                if (pingCounter >= 30) {
                    pingCounter = 0
                    try {
                        val start = System.currentTimeMillis()
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000)
                        socket.close()
                        stats.ping = (System.currentTimeMillis() - start).toInt()
                    } catch (_: Exception) {}

                }
                updateNotification("Connected")
            }
        }
    }

    private fun handleReconnect() {
        
        // Ne jamais fermer le service - juste signaler l'erreur
    }

    private fun updateStatus(status: ConnectionStatus, message: String = "") {
        
        try {
            currentStatus = status
            KighmuLogger.log(message, if (status == ConnectionStatus.ERROR) LogEntry.LogLevel.ERROR else LogEntry.LogLevel.INFO)
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                putExtra(EXTRA_STATUS, status.name)
                putExtra(EXTRA_MESSAGE, message)
            })
        } catch (_: Exception) {}
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = android.net.Uri.parse("package:$packageName") }
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun startWatchdog() {
        // Désactivé pour tunnel ZIVPN
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "KIGHMU VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, KighmuVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            this, 2,
            Intent(this, KighmuVpnService::class.java).apply { action = ACTION_START },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "KIGHMU VPN"

        val isConnected = text == "Connected"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
            .addAction(R.drawable.ic_vpn_key, "Reconnect", reconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isConnected && stats.connectedAt > 0) {
            builder.setUsesChronometer(true)
            builder.setWhen(stats.connectedAt)
            builder.setChronometerCountDown(false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

}
