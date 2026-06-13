with open("app/src/main/java/com/kighmu/vpn/engines/ZivpnEngine.kt", "r") as f:
    content = f.read()

old = """    companion object {
        const val TAG = "ZivpnEngine"
        const val LB_PORT    = 7777
        const val CLASH_PORT = 7890
        const val BASE_UZ_PORT = 7778
    }"""

new = """    companion object {
        const val TAG = "ZivpnEngine"
        const val LB_PORT    = 7777
        const val BASE_UZ_PORT = 7778
        fun findFreePort(preferred: Int): Int {
            // Essayer d'abord le port préféré
            try { java.net.ServerSocket(preferred).also { it.close() }; return preferred } catch (_: Exception) {}
            // Sinon trouver un port libre automatiquement
            return try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { preferred + 1 }
        }
    }

    private var clashPort: Int = 7890"""

if old in content:
    content = content.replace(old, new)
    with open("app/src/main/java/com/kighmu/vpn/engines/ZivpnEngine.kt", "w") as f:
        f.write(content)
    print("OK")
else:
    print("FAILED")
