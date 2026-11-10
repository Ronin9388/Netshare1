package com.example.netshareclone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var toggle: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvPass: TextView
    private lateinit var tvProxy: TextView
    private var updating = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocationPerm()) startHotspot()
            else setToggle(false, "Location permission is required for Wi-Fi hotspot")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        TextView(this).apply {
            text = "Start VPN Hotspot"; textSize = 26f; gravity = Gravity.CENTER
            root.addView(this)
        }

        toggle = SwitchCompat(this).apply {
            scaleX = 2.6f; scaleY = 2.6f
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = pad * 2; lp.bottomMargin = pad * 2
            layoutParams = lp
            setOnCheckedChangeListener { _, on -> if (!updating) if (on) onStartRequested() else stopHotspot() }
            root.addView(this)
        }

        tvStatus = label(root, "Status: Stopped", 16f)
        tvSsid = label(root, "SSID: —", 20f)
        tvPass = label(root, "Password: —", 20f)
        tvProxy = label(root, "Proxy (non-root clients): —", 16f)
        setContentView(root)
    }

    private fun label(parent: LinearLayout, t: String, size: Float) = TextView(this).apply {
        text = t; textSize = size; gravity = Gravity.CENTER
        setTextIsSelectable(true)
        setPadding(0, 16, 0, 16)
        parent.addView(this)
    }

    override fun onStart() {
        super.onStart()
        NetShareRouterService.listener = { render(it) }
        render(NetShareRouterService.current)
    }

    override fun onStop() {
        NetShareRouterService.listener = null
        super.onStop()
    }

    private fun render(s: NetShareRouterService.Companion.State) {
        setToggle(s.running, null)
        tvStatus.text = "Status: ${s.status}"
        tvSsid.text = "SSID: ${if (s.ssid.isEmpty()) "—" else s.ssid}"
        tvPass.text = "Password: ${if (s.password.isEmpty()) "—" else s.password}"
        tvProxy.text = "Proxy (non-root clients): ${if (s.proxy.isEmpty()) "—" else "SOCKS5 ${s.proxy}"}"
    }

    private fun setToggle(on: Boolean, msg: String?) {
        updating = true; toggle.isChecked = on; updating = false
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun hasLocationPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun onStartRequested() {
        val needed = mutableListOf<String>()
        if (!hasLocationPerm()) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else startHotspot()
    }

    private fun startHotspot() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            setToggle(false, "Turn on Location services (required by Android for hotspots)")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        ContextCompat.startForegroundService(
            this, Intent(this, NetShareRouterService::class.java).setAction(NetShareRouterService.ACTION_START)
        )
    }

    private fun stopHotspot() {
        startService(Intent(this, NetShareRouterService::class.java).setAction(NetShareRouterService.ACTION_STOP))
    }
}
