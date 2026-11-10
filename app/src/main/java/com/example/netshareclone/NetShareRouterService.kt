package com.example.netshareclone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service: LocalOnlyHotspot + SOCKS5 gateway egressing via the VPN network (works
 * without root) + optional root ip-rule/iptables NAT for transparent routing (no proxy needed).
 */
class NetShareRouterService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.netshareclone.START"
        const val ACTION_STOP = "com.example.netshareclone.STOP"
        const val SOCKS_PORT = 1080
        private const val CHANNEL_ID = "netshare_router"
        private const val NOTIF_ID = 42

        data class State(
            val running: Boolean,
            val ssid: String = "",
            val password: String = "",
            val proxy: String = "",
            val status: String = "Stopped"
        )

        @Volatile var current = State(false)
        var listener: ((State) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        fun publish(s: State) {
            current = s
            main.post { listener?.invoke(s) }
        }
    }

    private lateinit var cm: ConnectivityManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    private var ssid = ""
    private var pass = ""
    private var apIface: String? = null
    private var apSubnet: String? = null
    private var rootAvailable = false
    private var appliedTun: String? = null
    @Volatile private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                if (reservation == null) startHotspot()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Hotspot", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, NetShareRouterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Hotspot active")
            .setContentText("Sharing VPN connection with hotspot clients")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIF_ID, n)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startHotspot() {
        shuttingDown = false
        publish(State(true, status = "Starting hotspot…"))
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = res.softApConfiguration
                        ssid = c.ssid ?: ""
                        pass = c.passphrase ?: ""
                    } else {
                        val c: WifiConfiguration? = res.wifiConfiguration
                        ssid = c?.SSID ?: ""
                        pass = c?.preSharedKey ?: ""
                    }
                    Executors.newSingleThreadExecutor().execute { bringUpRouting() }
                }

                override fun onStopped() {
                    if (!shuttingDown) fail("Hotspot stopped by system")
                }

                override fun onFailed(reason: Int) {
                    fail("Hotspot failed (code $reason). Disable system tethering and retry.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            fail("Missing permission: ${e.message}")
        } catch (e: IllegalStateException) {
            fail("Wi-Fi/location unavailable: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        shutdown()
        publish(State(false, status = msg))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private data class Ap(val name: String, val addr: Inet4Address, val prefix: Int)

    private fun findApInterface(): Ap? {
        val re = Regex("^(ap|swlan|softap|wlan)\\d+$")
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (!ni.isUp || ni.isLoopback || ni.name == "wlan0" || !re.matches(ni.name)) return@forEach
            ni.interfaceAddresses.forEach { ia ->
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress)
                    return Ap(ni.name, a, ia.networkPrefixLength.toInt())
            }
        }
        return null
    }

    private fun subnetOf(a: Inet4Address, prefix: Int): String {
        val ip = ByteBuffer.wrap(a.address).int
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val n = ip and mask
        return "${n ushr 24 and 255}.${n ushr 16 and 255}.${n ushr 8 and 255}.${n and 255}/$prefix"
    }

    /** Dynamically discovers the active VPN network and its interface name (tun0, etc.). */
    private fun currentVpn(): Pair<Network, String>? {
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                val iface = cm.getLinkProperties(n)?.interfaceName ?: continue
                return n to iface
            }
        }
        return null
    }

    private fun bringUpRouting() {
        var ap: Ap? = null
        for (i in 0 until 20) {
            ap = findApInterface()
            if (ap != null) break
            Thread.sleep(500)
        }
        val a = ap
        val bindAddr: InetAddress? = a?.addr
        apIface = a?.name
        apSubnet = a?.let { subnetOf(it.addr, it.prefix) }

        try {
            server = if (bindAddr != null) ServerSocket(SOCKS_PORT, 64, bindAddr)
            else ServerSocket(SOCKS_PORT)
        } catch (e: Exception) {
            fail("Cannot open proxy port: ${e.message}")
            return
        }
        pool = Executors.newCachedThreadPool()
        pool!!.execute { acceptLoop() }

        rootAvailable = su("id")
        registerVpnWatcher()
        refreshRouting()
    }

    private fun registerVpnWatcher() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshRouting()
            override fun onLost(network: Network) = refreshRouting()
        }
        vpnCallback = cb
        cm.registerNetworkCallback(req, cb)
    }

    @Synchronized
    private fun refreshRouting() {
        if (shuttingDown) return
        val vpn = currentVpn()
        if (rootAvailable) {
            removeRootRules()
            if (vpn != null) applyRootRules(vpn.second)
        }
        val host = findApInterface()?.addr?.hostAddress ?: "<hotspot-ip>"
        val status = when {
            vpn == null -> "No active VPN found — using default network"
            rootAvailable -> "Root mode: routing via ${vpn.second} (transparent NAT + SOCKS5)"
            else -> "Non-root mode: routing via ${vpn.second} (SOCKS5 only)"
        }
        publish(State(true, ssid, pass, "$host:$SOCKS_PORT", status))
    }

    private fun su(vararg cmds: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds.joinToString("; ")))
        p.waitFor() == 0
    } catch (e: Exception) { false }

    private fun applyRootRules(tun: String) {
        val ap = apIface ?: return
        val net = apSubnet ?: return
        su(
            "echo 1 > /proc/sys/net/ipv4/ip_forward",
            "ip rule add from $net lookup $tun pref 9000",
            "iptables -w -t nat -A POSTROUTING -s $net -o $tun -j MASQUERADE",
            "iptables -w -I FORWARD -i $ap -o $tun -j ACCEPT",
            "iptables -w -I FORWARD -i $tun -o $ap -m state --state ESTABLISHED,RELATED -j ACCEPT"
        )
        appliedTun = tun
    }

    private fun removeRootRules() {
        val tun = appliedTun ?: return
        val ap = apIface ?: return
        val net = apSubnet ?: return
        su(
            "ip rule del from $net lookup $tun pref 9000",
            "iptables -w -t nat -D POSTROUTING -s $net -o $tun -j MASQUERADE",
            "iptables -w -D FORWARD -i $ap -o $tun -j ACCEPT",
            "iptables -w -D FORWARD -i $tun -o $ap -m state --state ESTABLISHED,RELATED -j ACCEPT"
        )
        appliedTun = null
    }

    private fun acceptLoop() {
        val s = server ?: return
        while (!shuttingDown) {
            try {
                val c = s.accept()
                pool?.execute { handleClient(c) }
            } catch (e: Exception) { break }
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            val inp = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            if (inp.readUnsignedByte() != 5) return
            inp.readFully(ByteArray(inp.readUnsignedByte()))
            out.write(byteArrayOf(5, 0)); out.flush()

            if (inp.readUnsignedByte() != 5) return
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte()
            val atyp = inp.readUnsignedByte()
            val host: String = when (atyp) {
                1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.toString(Charsets.UTF_8)
                4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                else -> { reply(out, 8); return }
            }
            val port = inp.readUnsignedShort()
            if (cmd != 1) { reply(out, 7); return }

            val net: Network? = currentVpn()?.first ?: cm.activeNetwork
            remote = Socket()
            try {
                net?.bindSocket(remote)
                val addr = if (net != null) net.getAllByName(host).first() else InetAddress.getByName(host)
                remote.connect(InetSocketAddress(addr, port), 10_000)
            } catch (e: Exception) {
                reply(out, 5); return
            }
            reply(out, 0)

            val r = remote
            pool?.execute { pipe(r.getInputStream(), out, client, r) }
            pipe(inp, r.getOutputStream(), client, r)
        } catch (_: Exception) {
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(i: InputStream, o: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n); o.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { a.close() } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun shutdown() {
        if (shuttingDown && reservation == null && server == null) return
        shuttingDown = true
        vpnCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        vpnCallback = null
        if (rootAvailable) removeRootRules()
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool?.shutdownNow(); pool = null
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        publish(State(false, status = "Stopped"))
    }
}
