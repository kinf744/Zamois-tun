import re

path = "app/src/main/java/com/kighmu/vpn/engines/AllEngines.kt"
with open(path, "r") as f:
    content = f.read()

# Fix 1: Améliorer xhttpSettings
old_xhttp = '''"xhttp" -> """
                "streamSettings": {
                    "network": "xhttp",
                    "security": "$tlsSec"$tlsBlock,
                    "xhttpSettings": { "path": "${xc.wsPath}", "host": "${xc.wsHost}", "mode": "auto" }
                }
            """.trimIndent()'''

new_xhttp = '''"xhttp" -> """
                "streamSettings": {
                    "network": "xhttp",
                    "security": "$tlsSec"$tlsBlock,
                    "xhttpSettings": {
                        "path": "${xc.wsPath}",
                        "host": "${xc.wsHost}",
                        "mode": "stream-up",
                        "scMaxConcurrentPosts": 16,
                        "scMinPostsIntervalMs": 10,
                        "scMaxEachPostBytes": 1000000,
                        "noSSEHeader": true,
                        "xPaddingBytes": "100-1000"
                    }
                }
            """.trimIndent()'''

# Fix 2: splithttp aussi
old_split = '''"splithttp" -> """
                "streamSettings": {
                    "network": "splithttp",
                    "security": "$tlsSec"$tlsBlock,
                    "splithttpSettings": { "path": "${xc.wsPath}", "host": "${xc.wsHost}", "mode": "auto" }
                }
            """.trimIndent()'''

new_split = '''"splithttp" -> """
                "streamSettings": {
                    "network": "splithttp",
                    "security": "$tlsSec"$tlsBlock,
                    "splithttpSettings": {
                        "path": "${xc.wsPath}",
                        "host": "${xc.wsHost}",
                        "mode": "stream-up",
                        "scMaxConcurrentPosts": 16,
                        "scMinPostsIntervalMs": 10,
                        "scMaxEachPostBytes": 1000000
                    }
                }
            """.trimIndent()'''

# Fix 3: MTU HevTun2Socks 8500 -> 1400 pour XrayEngine
old_mtu = "com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, it, 8500)"
new_mtu = "com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, it, 1400)"

changed = 0

if old_xhttp in content:
    content = content.replace(old_xhttp, new_xhttp)
    print("✅ Fix 1: xhttpSettings amélioré")
    changed += 1
else:
    print("⚠️  Fix 1: bloc xhttp non trouvé - vérifier indentation")

if old_split in content:
    content = content.replace(old_split, new_split)
    print("✅ Fix 2: splithttpSettings amélioré")
    changed += 1
else:
    print("⚠️  Fix 2: bloc splithttp non trouvé")

if old_mtu in content:
    content = content.replace(old_mtu, new_mtu)
    print("✅ Fix 3: MTU HevTun2Socks 8500 → 1400")
    changed += 1
else:
    print("⚠️  Fix 3: MTU 8500 non trouvé")

if changed > 0:
    with open(path, "w") as f:
        f.write(content)
    print(f"\n✅ {changed} fix(es) appliqué(s)")
else:
    print("\n❌ Aucun fix appliqué - fichier inchangé")
