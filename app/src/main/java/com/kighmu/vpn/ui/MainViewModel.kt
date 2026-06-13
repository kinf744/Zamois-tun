package com.kighmu.vpn.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kighmu.vpn.config.ConfigManager
import com.kighmu.vpn.models.*
import com.kighmu.vpn.vpn.KighmuVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configManager = ConfigManager(application)

    // ─── State Flows ──────────────────────────────────────────────────────────

    private val _config = MutableStateFlow(KighmuConfig())
    val config: StateFlow<KighmuConfig> = _config.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _stats = MutableStateFlow(VpnStats())
    val stats: StateFlow<VpnStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _importResult = MutableSharedFlow<String>()
    val importResult: SharedFlow<String> = _importResult.asSharedFlow()

    // ─── VPN Status Receiver ─────────────────────────────────────────────────

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(KighmuVpnService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(KighmuVpnService.EXTRA_MESSAGE) ?: ""
            _connectionStatus.value = ConnectionStatus.valueOf(status)
            _statusMessage.value = message
            _stats.value = KighmuVpnService.stats.copy()
        }
    }

    init {
        // Load config
        _config.value = configManager.loadCurrentConfig()

        // Register receiver
        val filter = IntentFilter(KighmuVpnService.BROADCAST_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(statusReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(statusReceiver, filter)
        }

        // Sync current service status
        _connectionStatus.value = KighmuVpnService.currentStatus
        _stats.value = KighmuVpnService.stats.copy()

        // Poll stats every second when connected
        viewModelScope.launch {
            while (true) {
                if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                    _stats.value = KighmuVpnService.stats.copy()
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        // Observe logs
        viewModelScope.launch {
            com.kighmu.vpn.utils.KighmuLogger.logFlow.collect { entry ->
                val current = _logs.value.toMutableList()
                current.add(entry)
                if (current.size > 200) current.removeAt(0)
                _logs.value = current
            }
        }
    }

    // ─── Config Operations ────────────────────────────────────────────────────

    fun saveConfig(config: KighmuConfig) {
        _config.value = config
        configManager.saveCurrentConfig(config)
    }

    fun updateTunnelMode(mode: TunnelMode) {
        saveConfig(_config.value.copy(tunnelMode = mode))
    }

    fun updateSshCredentials(ssh: SshCredentials) {
        saveConfig(_config.value.copy(sshCredentials = ssh))
    }

    fun updateSlowDns(dns: SlowDnsConfig) {
        saveConfig(_config.value.copy(slowDns = dns))
    }

    fun updateHttpProxy(proxy: HttpProxyConfig) {
        saveConfig(_config.value.copy(httpProxy = proxy))
    }

    fun updateSshSsl(ssl: SshSslConfig) {
        saveConfig(_config.value.copy(sshSsl = ssl))
    }

    fun updateXray(xray: XrayConfig) {
        saveConfig(_config.value.copy(xray = xray))
    }

    fun updateHysteria(hysteria: HysteriaConfig) {
        saveConfig(_config.value.copy(hysteria = hysteria))
    }

    fun importXrayJson(json: String): Boolean {
        val success = configManager.importXrayJson(json)
        if (success) _config.value = configManager.loadCurrentConfig()
        return success
    }

    // ─── VPN Control ─────────────────────────────────────────────────────────

    fun startVpn(context: Context): Intent? {
        // Check VPN permission
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) return vpnIntent

        // Permission already granted, start service
        val intent = Intent(context, KighmuVpnService::class.java)
            .apply { action = KighmuVpnService.ACTION_START }
        context.startForegroundService(intent)
        return null
    }

    fun stopVpn(context: Context) {
        _connectionStatus.value = com.kighmu.vpn.models.ConnectionStatus.DISCONNECTED
        try {
            val intent = Intent(context, KighmuVpnService::class.java)
                .apply { action = KighmuVpnService.ACTION_STOP }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    fun isConnected() = _connectionStatus.value == ConnectionStatus.CONNECTED
    fun isConnecting() = _connectionStatus.value == ConnectionStatus.CONNECTING ||
                         _connectionStatus.value == ConnectionStatus.RECONNECTING

    // ─── Import / Export ──────────────────────────────────────────────────────

    fun importConfig(@Suppress("UNUSED_PARAMETER") context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            when (val result = configManager.importFromUri(uri)) {
                is ConfigManager.ImportResult.Success -> {
                    _config.value = result.config
                    _importResult.emit("Config imported: ${result.config.configName}")
                }
                is ConfigManager.ImportResult.Expired ->
                    _importResult.emit("Config expired on ${java.util.Date(result.expiredAt)}")
                is ConfigManager.ImportResult.WrongDevice ->
                    _importResult.emit("Config locked: ${result.msg}")
                is ConfigManager.ImportResult.InvalidPassword ->
                    _importResult.emit("Wrong password: ${result.msg}")
                is ConfigManager.ImportResult.ParseError ->
                    _importResult.emit("Parse error: ${result.msg}")
                is ConfigManager.ImportResult.BurnedConfig ->
                    _importResult.emit("❌ Config déjà utilisée: ${result.msg}")
                is ConfigManager.ImportResult.AppMismatch ->
                    _importResult.emit("❌ Config incompatible: ${result.msg}")
            }
        }
    }

    fun exportConfig(@Suppress("UNUSED_PARAMETER") context: Context, uri: android.net.Uri) {
        viewModelScope.launch {
            val success = configManager.exportConfig(_config.value, uri)
            _importResult.emit(if (success) "Config exported successfully" else "Export failed")
        }
    }

    fun resetConfig() {
        saveConfig(com.kighmu.vpn.models.KighmuConfig())
    }

    fun clearLogs() {
        com.kighmu.vpn.utils.KighmuLogger.clearLogs()
        _logs.value = emptyList()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(statusReceiver)
        super.onCleared()
    }
}
