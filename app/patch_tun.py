with open("app/src/main/java/com/kighmu/vpn/vpn/KighmuVpnService.kt", "r") as f:
    content = f.read()

old = """    private fun readTunStats(): Pair<Long, Long> {
        return try {
            val lines = java.io.File("/proc/net/dev").readLines()
            var totalRx = 0L
            var totalTx = 0L
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.matches(Regex("tun\\\\d+:.*")) || trimmed.matches(Regex("vpn\\\\d*:.*"))) {
                    val parts = trimmed.replaceFirst(Regex("[^:]+:"), "").trim().split(Regex("\\\\s+"))
                    totalRx += parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    totalTx += parts.getOrNull(8)?.toLongOrNull() ?: 0L
                }
            }
            Pair(totalRx, totalTx)
        } catch (_: Exception) { Pair(0L, 0L) }
    }"""

new = """    private fun getTunIfaceName(): String? {
        return try {
            val fd = vpnInterface?.fd ?: return null
            val link = java.io.File("/proc/self/fd/$fd").canonicalPath
            // link ressemble a /dev/tun5 ou /dev/vpn0
            link.substringAfterLast("/").ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun readTunStats(): Pair<Long, Long> {
        return try {
            val ifaceName = getTunIfaceName()
            val lines = java.io.File("/proc/net/dev").readLines()
            var totalRx = 0L
            var totalTx = 0L
            for (line in lines) {
                val trimmed = line.trim()
                val matched = if (ifaceName != null) {
                    trimmed.startsWith("${'$'}ifaceName:")
                } else {
                    trimmed.matches(Regex("tun\\\\d+:.*")) || trimmed.matches(Regex("vpn\\\\d*:.*"))
                }
                if (matched) {
                    val parts = trimmed.replaceFirst(Regex("[^:]+:"), "").trim().split(Regex("\\\\s+"))
                    totalRx += parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    totalTx += parts.getOrNull(8)?.toLongOrNull() ?: 0L
                    KighmuLogger.info(TAG, "TUN_IFACE=${'$'}ifaceName rx=${'$'}totalRx tx=${'$'}totalTx")
                }
            }
            Pair(totalRx, totalTx)
        } catch (_: Exception) { Pair(0L, 0L) }
    }"""

if old in content:
    content = content.replace(old, new)
    with open("app/src/main/java/com/kighmu/vpn/vpn/KighmuVpnService.kt", "w") as f:
        f.write(content)
    print("OK")
else:
    print("FAILED")
