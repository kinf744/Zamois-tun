package com.kighmu.vpn.engines

import com.kighmu.vpn.utils.KighmuLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

class SocksBalancer(
    initialPorts: List<Int>,
    private val vpnService: android.net.VpnService? = null,
    private val maxBytesPerSec: Long = 0
) {
    companion object {
        const val TAG = "SocksBalancer"
        var BALANCER_PORT = 10900
        const val PIPE_BUFFER_SIZE = 65536
        const val MAX_THREADS = 500
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private val counter = AtomicInteger(0)
    private val globalTokens = java.util.concurrent.atomic.AtomicLong(0)
    private val lastRefillTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    @Volatile private var activePorts: List<Int> = initialPorts
    @Volatile private var healthyPorts: List<Int> = initialPorts
    private val failCount = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val threadPool = java.util.concurrent.ThreadPoolExecutor(
        20, MAX_THREADS, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val totalConnections      = AtomicInteger(0)
    private val successConnections    = AtomicInteger(0)
    private val failedConnections     = AtomicInteger(0)
    private val totalBytesTransferred = java.util.concurrent.atomic.AtomicLong(0)

    fun getBytesTransferred(): Long = totalBytesTransferred.get()
    fun resetBytesTransferred() = totalBytesTransferred.set(0)

    fun start() {
        running = true
        val ss = ServerSocket(0)
        BALANCER_PORT = ss.localPort
        serverSocket  = ss
        KighmuLogger.info(TAG, "Balancer demarre")
        Thread {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    totalConnections.incrementAndGet()
                    val targetPort = nextPort()
                    threadPool.execute { relay(client, targetPort) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Accept error: ${e.message}")
                }
            }
        }.apply { isDaemon = true; name = "kighmu-balancer" }.start()

        // Health check périodique des upstreams (toutes les 10s)
        Thread {
            while (running) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
                if (!running) break
                val ports = activePorts.toList()
                for (port in ports) {
                    val ok = try {
                        java.net.Socket("127.0.0.1", port).also { it.close() }; true
                    } catch (_: Exception) { false }
                    if (ok) {
                        markPortSuccess(port)
                    } else {
                        val fails = (failCount[port] ?: 0) + 1
                        failCount[port] = fails
                        if (fails >= 5) {
                            val h = healthyPorts.filter { it != port }
                            if (h.isNotEmpty()) {
                                healthyPorts = h
                                KighmuLogger.warning(TAG, "Health: port $port hors ligne")
                            }
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "kighmu-health" }.start()
    }

    fun updatePorts(newPorts: List<Int>) {
        if (newPorts.isNotEmpty()) {
            activePorts  = newPorts.toList()
            healthyPorts = newPorts.toList()
            failCount.clear()
            counter.set(0)

        }
    }

    fun stop() {
        running = false
        threadPool.shutdown()
        try { serverSocket?.close() } catch (_: Exception) {}
            }

    private fun nextPort(): Int {
        val current = healthyPorts.ifEmpty { activePorts }
        if (current.isEmpty()) return 10800
        return current[counter.getAndIncrement() % current.size]
    }

    private fun markPortFailed(port: Int) {
        val fails = (failCount[port] ?: 0) + 1
        failCount[port] = fails
        if (fails >= 5) {
            val h = healthyPorts.filter { it != port }
            if (h.isNotEmpty()) {
                healthyPorts = h
                KighmuLogger.warning(TAG, "Port $port retire echecs=$fails healthy=$healthyPorts")
            }
        }
    }

    private fun markPortSuccess(port: Int) {
        failCount[port] = 0
        if (!healthyPorts.contains(port) && activePorts.contains(port)) {
            healthyPorts = (healthyPorts + port).distinct()

        }
    }

    private fun connectToPort(targetPort: Int): Socket {
        val server = Socket()
        // NE PAS proteger les connexions vers 127.0.0.1 - elles sont locales
        // vpnService.protect() est uniquement pour les connexions vers serveurs externes
        server.receiveBufferSize = PIPE_BUFFER_SIZE
        server.sendBufferSize    = PIPE_BUFFER_SIZE
        server.tcpNoDelay = true
        server.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
        return server
    }

    private fun relay(client: Socket, targetPort: Int) {
        try {
            client.soTimeout = 120_000
            client.setPerformancePreferences(0, 0, 1)
            client.receiveBufferSize = PIPE_BUFFER_SIZE
            client.sendBufferSize    = PIPE_BUFFER_SIZE
            client.tcpNoDelay = true

            var server: Socket? = null
            val candidates = listOf(targetPort) + activePorts.filter { it != targetPort }
            for (port in candidates) {
                try {
                    server = connectToPort(port)
                    break
                } catch (_: Exception) {}
            }

            if (server == null) {
                failedConnections.incrementAndGet()
                markPortFailed(targetPort)
                try { client.close() } catch (_: Exception) {}
                return
            }

            successConnections.incrementAndGet()
            markPortSuccess(targetPort)

            val s = server!!
            s.soTimeout = 120_000
            s.tcpNoDelay = true

            threadPool.execute {
                try { pipe(client.getInputStream(), s.getOutputStream()) } catch (_: Exception) {}
            }
            try { pipe(s.getInputStream(), client.getOutputStream()) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            try { s.close()      } catch (_: Exception) {}

        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("ECONNREFUSED") && !msg.contains("Connection refused") &&
                !msg.contains("failed to connect") && !msg.contains("isConnected failed")) {
                KighmuLogger.error(TAG, "Relay error $targetPort: $msg")
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun acquireTokens(bytes: Int) {
        if (maxBytesPerSec <= 0) return
        while (true) {
            val now     = System.currentTimeMillis()
            val elapsed = now - lastRefillTime.get()
            if (elapsed >= 50) {
                val refill  = maxBytesPerSec * elapsed / 1000
                val current = globalTokens.get()
                val newVal  = minOf(maxBytesPerSec, current + refill)
                if (globalTokens.compareAndSet(current, newVal)) lastRefillTime.set(now)
            }
            val current = globalTokens.get()
            if (current >= bytes) {
                if (globalTokens.compareAndSet(current, current - bytes)) return
            } else {
                Thread.sleep(((bytes - current) * 1000 / maxBytesPerSec) + 1)
            }
        }
    }

    private fun pipe(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n == -1) break
                acquireTokens(n)
                out.write(buf, 0, n)
                totalBytesTransferred.addAndGet(n.toLong())
                if (inp.available() <= 0) out.flush()
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Timeout normal → connexion zombie tuée proprement
        } catch (_: Exception) {}
    }
}
