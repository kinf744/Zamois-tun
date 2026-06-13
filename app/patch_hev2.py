with open("app/src/main/java/com/kighmu/vpn/engines/HevTun2Socks.kt", "r") as f:
    content = f.read()

old = """object HevTun2Socks {
    const val TAG = "HevTun2Socks"
    private var loaded = false
    @Volatile private var running = false
    @Volatile private var stopping = false"""

new = """object HevTun2Socks {
    const val TAG = "HevTun2Socks"
    private var loaded = false
    private val lock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var running = false"""

content = content.replace(old, new)

old2 = """    fun start(context: Context, fd: Int, socksPort: Int, vpnService: android.net.VpnService, mtu: Int = 1500, rateLimitBps: Long = 0) {
        // Attendre que le stop précédent soit terminé (max 3s)
        var waited = 0
        while (stopping && waited < 3000) { Thread.sleep(100); waited += 100 }
        if (running) { stop(); Thread.sleep(300) }
        val config = buildConfig(socksPort, mtu, rateLimitBps)
        val configFile = File(context.cacheDir, "hev_config.yaml")
        configFile.writeText(config)
        Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")
        running = true
        hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }"""

new2 = """    fun start(context: Context, fd: Int, socksPort: Int, vpnService: android.net.VpnService, mtu: Int = 1500, rateLimitBps: Long = 0) {
        lock.lock()
        try {
            if (running) {
                hev.htproxy.TProxyService.TProxyStopService()
                running = false
            }
            val config = buildConfig(socksPort, mtu, rateLimitBps)
            val configFile = File(context.cacheDir, "hev_config.yaml")
            configFile.writeText(config)
            Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            running = true
        } finally {
            lock.unlock()
        }
    }"""

content = content.replace(old2, new2)

old3 = """    fun stop() {
        if (loaded && running) {
            stopping = true
            running = false
            hev.htproxy.TProxyService.TProxyStopService()
            Thread.sleep(200)
            stopping = false
        }
    }"""

new3 = """    fun stop() {
        lock.lock()
        try {
            if (loaded && running) {
                running = false
                hev.htproxy.TProxyService.TProxyStopService()
            }
        } finally {
            lock.unlock()
        }
    }"""

content = content.replace(old3, new3)

with open("app/src/main/java/com/kighmu/vpn/engines/HevTun2Socks.kt", "w") as f:
    f.write(content)
print("OK")
