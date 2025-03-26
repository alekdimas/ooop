package com.example.individualproxy.model.data.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.individualproxy.model.MyApp
import com.example.individualproxy.model.data.api.VpnApi
import com.example.individualproxy.model.data.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class VpnConnectionService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val handler = Handler(Looper.getMainLooper())
    private val vpnApi: VpnApi by lazy { (application as MyApp).vpnApi }
    private val sessionManager: SessionManager by lazy { (application as MyApp).sessionManager }

    private val dialerController = object : DialerController {
        override fun protectFd(socket: Long): Boolean {
            val result = try {
                val success = protect(socket.toInt())
                // Убираем спам в логах, логируем только ошибки
                if (!success) Log.e("VPN_SERVICE", "Failed to protect socket $socket")
                success
            } catch (e: Exception) {
                Log.e("VPN_SERVICE", "Socket protection failed", e)
                false
            }
            return result
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LibXray.registerDialerController(dialerController)
        Log.d("VPN_SERVICE", "Service created")
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel("vpn_channel") == null) {
            NotificationChannel(
                "vpn_channel",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN service using Xray-core"
                notificationManager.createNotificationChannel(this)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            Log.d("VPN_SERVICE", "Received STOP_VPN action")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, "vpn_channel")
                .setContentTitle("Xray VPN Active")
                .setContentText("Protected by Xray-core")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )

        currentConfig = intent?.getStringExtra("CONFIG")
        currentConfig?.let {
            startVpn(it)
            Log.d("VPN_SERVICE", "Using config: $it")
        } ?: run {
            Log.e("VPN_SERVICE", "No config provided in intent")
            stopSelf()
        }

        return START_STICKY
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun startVpn(config: String) {
        if (isRunning.get()) return

        scope.launch {
            try {
                val builder = Builder().apply {
                    setSession("Xray VPN")
                    setMtu(1500)
                    addAddress("172.19.0.1", 28)
                    addRoute("0.0.0.0", 0) // Весь IPv4
                    addRoute("::", 0) // Весь IPv6
                    addDisallowedApplication(packageName)
                    addDnsServer("8.8.8.8")
                    addDnsServer("8.8.4.4")
                    setBlocking(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setMetered(false)
                    }
                }

                vpnInterface = builder.establish() ?: throw IOException("Failed to create VPN interface")
                Log.d("VPN_SERVICE", "VPN interface created: ${vpnInterface!!.fileDescriptor}")

                val decodedBytes = Base64.decode(config)
                val jsonConfig = String(decodedBytes, Charsets.UTF_8)
                Log.d("VPN_SERVICE", "Decoded JSON config: $jsonConfig")
                val configObj = JSONObject(jsonConfig)
                val configPath = configObj.getString("ConfigPath")
                val xrayConfigRaw = File(configPath).readText()
                Log.d("VPN_SERVICE", "Xray config from file: $xrayConfigRaw")

                // Проверка доступности файла
                val configFile = File(configPath)
                if (!configFile.exists() || !configFile.canRead()) {
                    throw IllegalStateException("Config file not found or unreadable: $configPath")
                }

                // Передаём оригинальный Base64-конфиг с DatDir и ConfigPath
                val base64Response = LibXray.runXray(config)
                val decodedResponseBytes = Base64.decode(base64Response)
                val jsonResponse = String(decodedResponseBytes, Charsets.UTF_8)
                Log.d("VPN_SERVICE", "Xray response: $jsonResponse")

                val responseJson = JSONObject(jsonResponse)
                if (!responseJson.optBoolean("success", false)) {
                    throw IllegalStateException("Xray error: ${responseJson.toString(2)}")
                }

                delay(1000) // Даём Xray время запуститься

                isRunning.set(true)
                Log.d("VPN_SERVICE", "VPN started successfully")

                // Логи ошибок Xray
                val errorLogFile = File("/data/user/0/com.example.individualproxy/files/xray_error.log")
                if (errorLogFile.exists()) {
                    val errors = errorLogFile.readText()
                    if (errors.isNotEmpty()) {
                        Log.e("VPN_SERVICE", "Xray error log: $errors")
                    }
                }

            } catch (e: Exception) {
                Log.e("VPN_SERVICE", "Failed to start VPN", e)
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        Log.d("VPN_SERVICE", "Stopping VPN")
        isRunning.set(false)
        try {
            LibXray.stopXray()
            Log.d("VPN_SERVICE", "Xray stop called")
        } catch (e: Exception) {
            Log.e("VPN_SERVICE", "Failed to stop Xray", e)
        }
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d("VPN_SERVICE", "VPN interface closed")
        } catch (e: IOException) {
            Log.e("VPN_SERVICE", "Failed to close VPN interface", e)
        }
        resetNetworkSettings()
    }

    private val checkSubscription = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sessionManager.getUserToken().let { token ->
                        val response = vpnApi.checkSubscription(com.example.individualproxy.model.data.vpn.RequestVpnStatus(token))
                        if (response.isSuccessful && !response.body()?.subscriptionIsActive!!) {
                            stopSelf()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VPN_SERVICE", "Subscription check failed", e)
                } finally {
                    scheduleNextCheck()
                }
            }
        }
    }

    private fun scheduleNextCheck() {
        if (isRunning.get()) {
            handler.postDelayed(checkSubscription, 900_000)
        }
    }

    override fun onDestroy() {
        Log.d("VPN_SERVICE", "Service onDestroy called. Running state: ${isRunning.get()}")
        scope.cancel()
        handler.removeCallbacks(checkSubscription)
        stopForeground(true)
        resetNetworkSettings()
        Log.d("VPN_SERVICE", "Service destroyed")
        super.onDestroy()
    }

    private fun resetNetworkSettings() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.bindProcessToNetwork(null)
            Log.d("VPN_SERVICE", "Network unbound from process in service")

            val dnsResetCommand = "setprop net.dns1 10.0.2.3"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", dnsResetCommand))
            Log.d("VPN_SERVICE", "DNS reset to 10.0.2.3 attempted")

            val dnsResetPublic = "setprop net.dns1 8.8.8.8"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", dnsResetPublic))
            Log.d("VPN_SERVICE", "DNS reset to 8.8.8.8 attempted")
        } catch (e: Exception) {
            Log.e("VPN_SERVICE", "Failed to reset network settings in service", e)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1337
    }
}
