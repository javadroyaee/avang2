package com.jrweb.avangvpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jrweb.avangvpn.network.SubParser
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class AvangVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.jrweb.avangvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.jrweb.avangvpn.DISCONNECT"
        const val EXTRA_CONFIG_URI = "config_uri"
        val isRunningFlow = MutableStateFlow(false)
        val lastErrorFlow = MutableStateFlow<String?>(null)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val uri = intent.getStringExtra(EXTRA_CONFIG_URI) ?: return START_NOT_STICKY
                startVpn(uri)
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(configUri: String) {
        stopVpn()
        lastErrorFlow.value = null
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "avang_vpn_channel")
            .setContentTitle("آونگ وی‌پی‌ان")
            .setContentText("تونل امن فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)

        try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")

            coreController = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
                override fun startup(): Long = 0
                override fun shutdown(): Long {
                    stopVpn()
                    return 0
                }
            })

            val xrayConfigJson = SubParser.buildXrayJsonFromUri(configUri, 10808)
            coreController?.startLoop(xrayConfigJson)

            val builder = Builder()
                .setSession("Avang VPN")
                .addAddress("10.1.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish() ?: throw Exception("خطا در ایجاد رابط TUN اندروید")

            val tproxyConf = File(cacheDir, "tproxy.conf")
            tproxyConf.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 10.1.0.2
                socks5:
                  port: 10808
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  task-stack-size: 81920
                """.trimIndent()
            )
            TProxyService.TProxyStartService(tproxyConf.absolutePath, vpnInterface!!.fd)
            isRunningFlow.value = true
        } catch (e: Throwable) {
            Log.e("AvangVPN", "VPN Start Error", e)
            lastErrorFlow.value = "خطای سرویس VPN: ${e.message ?: "نامشخص"}"
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunningFlow.value = false
        try { TProxyService.TProxyStopService() } catch (e: Throwable) { Log.e("AvangVPN", "TProxy Stop Error", e) }
        try {
            coreController?.stopLoop()
            vpnInterface?.close()
        } catch (_: Throwable) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("avang_vpn_channel", "سرویس اتصال آونگ وی‌پی‌ان", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
