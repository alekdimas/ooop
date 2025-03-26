package com.example.individualproxy.viewModel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.individualproxy.model.data.repository.VpnRepository
import com.example.individualproxy.model.data.services.VpnConnectionService
import com.example.individualproxy.model.data.vpn.Country
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class VpnViewModel(
    application: Application,
    private val repository: VpnRepository
) : AndroidViewModel(application) {

    val subscriptionActive = MutableLiveData<Boolean>()
    val errorMessage = MutableLiveData<String?>()

    private val _isVpnEnabled = MutableStateFlow(false)
    val isVpnEnabled: StateFlow<Boolean> = _isVpnEnabled

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _countriesError = MutableStateFlow<String?>(null)
    val countriesError: StateFlow<String?> = _countriesError.asStateFlow()

    init {
        viewModelScope.launch {
            _isVpnEnabled.value = repository.getVpnEnabledState()

            repository.getVpnEnabledFlow().collect { enabled ->
                _isVpnEnabled.value = enabled
            }
        }
    }

    fun loadCountries() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getCountries().fold(
                onSuccess = { countries ->
                    _countries.value = countries
                    _countriesError.value = null
                },
                onFailure = { error ->
                    _countriesError.value = error.message
                }
            )
            _isLoading.value = false
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun connectToVpn(country: String?) {
        viewModelScope.launch {
            repository.getVpnConfig(country).fold(
                onSuccess = { response ->
                    subscriptionActive.value = response.subscriptionIsActive
                    if (response.subscriptionIsActive) {
                        Log.d("VPN_SERVICE", "Received VPN config: ${response.config}")
                        val configPath = saveConfigToInternalStorage(response.config)
                        val datDirectoryPath = getOrCreateDatFilesDirectory()
                        val jsonConfig = createConfigJson(datDirectoryPath, configPath)
                        val base64Config = Base64.encode(jsonConfig.toByteArray())
                        startVpnService(base64Config)
                        repository.setVpnEnabled(true)
                    }
                },
                onFailure = { throwable ->
                    errorMessage.value = throwable.message
                    val fallbackConfig = """
{
    "log": {
        "loglevel": "debug",
        "access": "/data/user/0/com.example.individualproxy/files/xray_access.log",
        "error": "/data/user/0/com.example.individualproxy/files/xray_error.log"
    },
    "dns": {
        "servers": [
            "8.8.8.8",
            "8.8.4.4"
        ]
    },
    "inbounds": [
        {
            "port": 10808,
            "protocol": "socks",
            "settings": {
                "auth": "noauth",
                "udp": true,
                "ip": "127.0.0.1"
            },
            "tag": "socks-in"
        }
    ],
    "outbounds": [
        {
            "protocol": "vless",
            "settings": {
                "vnext": [
                    {
                        "address": "95.216.125.17",
                        "port": 443,
                        "users": [
                            {
                                "id": "b37458f5-8efa-418c-9f9f-0cfbbf41e5fe",
                                "encryption": "none",
                                "flow": "xtls-rprx-vision"
                            }
                        ]
                    }
                ]
            },
            "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                    "serverName": "asus.com",
                    "fingerprint": "chrome",
                    "publicKey": "YNU3-NsquulMjTPTvxCObCUCEWzvYaHtpOctezNeGSo",
                    "shortId": "1dfc79e3",
                    "spiderX": "/"
                }
            },
            "tag": "proxy"
        }
    ],
    "routing": {
        "domainStrategy": "IPIfNonMatch",
        "rules": [
            {
                "type": "field",
                "inboundTag": ["socks-in"],
                "outboundTag": "proxy"
            }
        ]
    }
}
""".trimIndent()
                    Log.d("VPN_SERVICE", "Using default config: $fallbackConfig")
                    val configPath = saveConfigToInternalStorage(fallbackConfig)
                    val datDirectoryPath = getOrCreateDatFilesDirectory()
                    val jsonConfig = createConfigJson(datDirectoryPath, configPath)
                    val base64Config = Base64.encode(jsonConfig.toByteArray())
                    startVpnService(base64Config)
                    repository.setVpnEnabled(true)
                }
            )
        }
    }

    private fun saveConfigToInternalStorage(config: String): String {
        val context = getApplication<Application>().applicationContext
        try {
            Log.d("VPN_SERVICE", "Saving config to internal storage: $config")
            JSONObject(config)
            Log.d("VPN_SERVICE", "VPN configuration JSON is valid")
        } catch (e: Exception) {
            Log.e("VPN_SERVICE", "Invalid VPN configuration JSON", e)
            throw IllegalStateException("Invalid VPN configuration JSON: ${e.message}")
        }
        return File(context.filesDir, "vpn_config.json").apply {
            writeText(config)
        }.absolutePath
    }

    private fun getOrCreateDatFilesDirectory(): String {
        val context = getApplication<Application>().applicationContext
        val datDir = File(context.filesDir, "dat_files").apply {
            if (!exists()) mkdir()
        }
        copyDatFilesFromAssets(datDir)
        return datDir.absolutePath
    }

    private fun copyDatFilesFromAssets(targetDir: File) {
        val context = getApplication<Application>().applicationContext
        val filesToCopy = listOf("geoip.dat", "geosite.dat")
        filesToCopy.forEach { fileName ->
            val targetFile = File(targetDir, fileName)
            if (!targetFile.exists()) {
                try {
                    context.assets.open(fileName).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    errorMessage.postValue("Failed to copy $fileName: ${e.message}")
                }
            }
        }
    }

    private fun createConfigJson(datDirectory: String, configPath: String): String {
        return JSONObject().apply {
            put("DatDir", datDirectory)
            put("ConfigPath", configPath)
        }.toString()
    }

    private fun startVpnService(base64Config: String? = null) {
        val intent = Intent(getApplication(), VpnConnectionService::class.java).apply {
            putExtra("CONFIG", base64Config)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun disconnectVpn() {
        viewModelScope.launch {
            Log.d("VPN_SERVICE", "Disconnecting VPN")
            repository.setVpnEnabled(false)
            stopVpnService()
            repository.clearConfig()
            launch {
                repository.releaseConfig().fold(
                    onSuccess = { Log.d("VPN_SERVICE", "Config released successfully") },
                    onFailure = { e -> Log.e("VPN_SERVICE", "Failed to release config", e) }
                )
            }
            try {
                resetNetworkSettings()
                Log.d("VPN_SERVICE", "Network reset attempted")
            } catch (e: SecurityException) {
                Log.e("VPN_SERVICE", "Failed to reset network settings", e)
                errorMessage.postValue("Failed to reset network settings: ${e.message}")
            }
        }
    }

    private fun stopVpnService() {
        val intent = Intent(getApplication(), VpnConnectionService::class.java).apply {
            action = "STOP_VPN"
        }
        getApplication<Application>().startService(intent)
        Log.d("VPN_SERVICE", "Stop VPN service intent sent")
    }

    private fun resetNetworkSettings() {
        val context = getApplication<Application>().applicationContext
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.bindProcessToNetwork(null)
            Log.d("VPN_SERVICE", "Network unbound from process")

            val dnsResetCommand = "setprop net.dns1 10.0.2.3"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", dnsResetCommand))
            Log.d("VPN_SERVICE", "DNS reset to 10.0.2.3 attempted")

            val dnsResetPublic = "setprop net.dns1 8.8.8.8"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", dnsResetPublic))
            Log.d("VPN_SERVICE", "DNS reset to 8.8.8.8 attempted")

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("VPN_SERVICE", "Network is available: $network")
                    connectivityManager.bindProcessToNetwork(network)
                    try {
                        connectivityManager.reportNetworkConnectivity(network, true)
                        Log.d("VPN_SERVICE", "Reported network connectivity as available")
                    } catch (e: SecurityException) {
                        Log.e("VPN_SERVICE", "Failed to report network connectivity", e)
                        errorMessage.postValue("Failed to report network connectivity: ${e.message}")
                    }
                }
                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d("VPN_SERVICE", "Network is lost: $network")
                    connectivityManager.bindProcessToNetwork(null)
                }
            })
        } catch (e: SecurityException) {
            Log.e("VPN_SERVICE", "Failed to reset network settings", e)
            errorMessage.postValue("Failed to reset network settings: ${e.message}")
        }
    }
}

data class Country(val code: String, val name: String)
