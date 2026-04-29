package com.blemesh.router.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.blemesh.router.router.MeshRouterService

class MainActivity : ComponentActivity() {

    private lateinit var headerView: TextView
    private lateinit var idView: TextView
    private lateinit var blePeersView: TextView
    private lateinit var transportsView: TextView
    private lateinit var statsView: TextView
    private lateinit var batteryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = requiredPermissions().all { result[it] == true }
        if (granted) {
            startRouterService()
            headerView.text = "Running"
        } else {
            headerView.text = "Permissions denied. Grant Bluetooth permissions in Settings, then reopen."
        }
        refreshBatteryButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())

        if (haveAllPermissions()) {
            startRouterService()
            headerView.text = "Running"
        } else {
            headerView.text = "Requesting permissions..."
            requestPermissions.launch(requiredPermissions())
        }
        refreshBatteryButton()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryButton()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    // --- UI construction ---

    private fun buildRootView(): ScrollView {
        val title = TextView(this).apply {
            text = "BLE Mesh Router"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        headerView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }

        idView = monoBlock()
        blePeersView = monoBlock()
        transportsView = monoBlock()
        statsView = monoBlock()

        batteryButton = Button(this).apply {
            text = "Disable battery optimization"
            setOnClickListener { requestBatteryExemption() }
        }
        val settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MeshRouterService::class.java))
                headerView.text = "Stopped"
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.START
            setPadding(48, 96, 48, 48)
        }
        column.addView(title)
        column.addView(headerView)
        column.addView(sectionHeader("Identity"))
        column.addView(idView)
        column.addView(sectionHeader("BLE peers"))
        column.addView(blePeersView)
        column.addView(sectionHeader("Router peers (Wi-Fi)"))
        column.addView(transportsView)
        column.addView(sectionHeader("Stats"))
        column.addView(statsView)
        column.addView(batteryButton)
        column.addView(settingsButton)
        column.addView(stopButton)

        return ScrollView(this).apply {
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun monoBlock(): TextView = TextView(this).apply {
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(0, 0, 0, 16)
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    // --- Refresh ---

    private fun refresh() {
        val snap = MeshRouterService.INSTANCE?.snapshot()
        if (snap == null) {
            idView.text = "(service not running)"
            blePeersView.text = ""
            transportsView.text = ""
            statsView.text = ""
            return
        }
        val ssidLine = if (snap.configuredSsid.isBlank()) "(no Wi-Fi configured)"
        else "Wi-Fi: " + snap.configuredSsid
        idView.text = snap.peerID.rawValue + "\n" + ssidLine
        blePeersView.text = if (snap.blePeers.isEmpty()) {
            "(none)"
        } else {
            snap.blePeers.joinToString("\n") { "  " + it.rawValue.take(8) + "..." }
        }
        transportsView.text = snap.transports.joinToString("\n\n") { t ->
            val avail = if (t.available) "available" else "unavailable"
            val peers = if (t.peers.isEmpty()) "    (no peers)"
            else t.peers.joinToString("\n") { "    " + it.rawValue.take(8) + "..." }
            "  ${t.name}  ($avail)\n$peers"
        }
        statsView.text = buildString {
            append("BLE -> bridge:  ").append(snap.bleToBridge).append('\n')
            append("bridge -> BLE:  ").append(snap.bridgeToBle)
        }
    }

    // --- Permissions / battery ---

    private fun startRouterService() {
        val intent = Intent(this, MeshRouterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // Wi-Fi Aware/Direct discovery requires fine location below API 33
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms.toTypedArray()
    }

    private fun haveAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshBatteryButton() {
        batteryButton.visibility =
            if (isBatteryOptimizationIgnored()) android.view.View.GONE else android.view.View.VISIBLE
    }

    companion object {
        private const val REFRESH_MS = 2_000L
    }
}
