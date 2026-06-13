path = "app/src/main/java/com/kighmu/vpn/vpn/KighmuVpnService.kt"
with open(path, "r") as f:
    content = f.read()

old = ".setMtu(1500)\n                        .addDisallowedApplication(packageName)"
new = ".setMtu(1400)\n                        .addDisallowedApplication(packageName)"

if old in content:
    content = content.replace(old, new)
    with open(path, "w") as f:
        f.write(content)
    print("✅ MTU tempVPN 1500 → 1400")
else:
    print("⚠️  Pattern non trouvé")
