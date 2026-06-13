with open("app/src/main/java/com/kighmu/vpn/engines/ZivpnEngine.kt", "r") as f:
    content = f.read()

old = """    override suspend fun start(): Int {
        running = true
        serverConnected = false"""

new = """    override suspend fun start(): Int {
        running = true
        serverConnected = false
        clashPort = findFreePort(7890)"""

if old in content:
    content = content.replace(old, new)
    with open("app/src/main/java/com/kighmu/vpn/engines/ZivpnEngine.kt", "w") as f:
        f.write(content)
    print("OK")
else:
    print("FAILED")
