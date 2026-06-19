[1mdiff --git a/app/src/main/java/com/kighmu/vpn/engines/MultiXraySlowDnsEngine.kt b/app/src/main/java/com/kighmu/vpn/engines/MultiXraySlowDnsEngine.kt[m
[1mindex baa3a6c..0dfc315 100644[m
[1m--- a/app/src/main/java/com/kighmu/vpn/engines/MultiXraySlowDnsEngine.kt[m
[1m+++ b/app/src/main/java/com/kighmu/vpn/engines/MultiXraySlowDnsEngine.kt[m
[36m@@ -279,12 +279,15 @@[m [mclass MultiXraySlowDnsEngine([m
                                 synchronized(dnsttEngines) {[m
                                     if (idx < dnsttEngines.size) dnsttEngines[idx] = newDnstt[m
                                 }[m
[31m-                                val alivePorts = synchronized(xrayEngines) {[m
[31m-                                    xrayEngines.mapIndexedNotNull { i, e ->[m
[31m-                                        if (e.isRunning()) activePorts.getOrNull(i) else null[m
[32m+[m[32m                                val updatedPorts = mutableListOf<Int>()[m
[32m+[m[32m                                synchronized(xrayEngines) {[m
[32m+[m[32m                                    xrayEngines.forEach { e ->[m
[32m+[m[32m                                        val p = e.getSocksPort()[m
[32m+[m[32m                                        if (e.isRunning() && p > 0) updatedPorts.add(p)[m
                                     }[m
                                 }[m
[31m-                                if (alivePorts.isNotEmpty()) socksBalancer?.updatePorts(alivePorts)[m
[32m+[m[32m                                activePorts = updatedPorts[m
[32m+[m[32m                                if (updatedPorts.isNotEmpty()) socksBalancer?.updatePorts(updatedPorts)[m
                                 KighmuLogger.info(TAG, "XrayEngine[$idx] warm replacement OK port=$newPort")[m
                                 try { oldXray?.stop() } catch (_: Exception) {}[m
                             } else {[m
