package com.blemesh.router.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import com.blemesh.router.router.BeaconIdentity
import com.blemesh.router.router.MeshRouterService
import com.blemesh.router.router.WifiCredentials
import com.blemesh.router.router.WifiNetworkApplicator

/**
 * Settings screen for the router-backbone Wi-Fi credentials.
 *
 * Saving installs a [android.net.wifi.WifiNetworkSuggestion] (API 29+) so the
 * device auto-associates to the configured SSID whenever it's in range. The
 * user must approve the suggestion via the system notification on first save.
 */
class ConfigActivity : ComponentActivity() {

    private lateinit var ssidField: EditText
    private lateinit var pskField: EditText
    private lateinit var stageField: EditText
    private lateinit var stageStatus: TextView
    private lateinit var beaconId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
    }

    override fun onResume() {
        super.onResume()
        val saved = WifiCredentials.load(this)
        ssidField.setText(saved.ssid)
        pskField.setText(saved.psk)
        stageField.setText(BeaconIdentity.loadStageId(this) ?: "")
        updateStageStatus()
    }

    private fun buildRootView(): ScrollView {
        val title = TextView(this).apply {
            text = "Router Wi-Fi backbone"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val help = TextView(this).apply {
            text = "Enter the SSID and PSK of the Wi-Fi network the routers " +
                "should associate with. Once saved, Android will ask you " +
                "to approve the network suggestion. Other routers on the " +
                "same network will be discovered automatically via mDNS."
            textSize = 13f
            setPadding(0, 16, 0, 24)
        }

        ssidField = EditText(this).apply {
            hint = "SSID"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        pskField = EditText(this).apply {
            hint = "PSK (WPA2 passphrase)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { onSave() }
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { onClear() }
        }

        beaconId = BeaconIdentity.load(this)
        val beaconHeader = TextView(this).apply {
            text = "Beacon"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }
        val beaconHelp = TextView(this).apply {
            text = "This router advertises the id below as a BLE beacon. Register " +
                "it on the loxation server as a fixed/location beacon at the " +
                "router's install location; nearby loxation clients will then " +
                "report sightings with no app update."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        val beaconValue = TextView(this).apply {
            text = beaconId
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 2)
        }
        val beaconUuid = TextView(this).apply {
            text = BeaconIdentity.asDashedUuid(beaconId)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        }
        val copyBeaconButton = Button(this).apply {
            text = "Copy id"
            setOnClickListener { onCopyBeaconId() }
        }

        val stageHelp = TextView(this).apply {
            text = "Optional stage (sublocation) id — lowercase letters, digits, " +
                "hyphens. When set, the beacon advertisement carries a stage " +
                "hash and updated loxation clients bind this router to that " +
                "declared stage live, overriding the pack's static binding " +
                "(router swap without a pack re-upload). Leave empty for the " +
                "legacy advertisement. Saving re-advertises immediately."
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        stageField = EditText(this).apply {
            hint = "Stage id (e.g. mainstage)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        stageStatus = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 4, 0, 4)
        }
        val saveStageButton = Button(this).apply {
            text = "Save stage"
            setOnClickListener { onSaveStage() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.START
            setPadding(48, 96, 48, 48)
        }
        column.addView(title)
        column.addView(help)
        column.addView(label("SSID"))
        column.addView(ssidField)
        column.addView(label("PSK"))
        column.addView(pskField)
        column.addView(saveButton)
        column.addView(clearButton)
        column.addView(beaconHeader)
        column.addView(beaconHelp)
        column.addView(beaconValue)
        column.addView(beaconUuid)
        column.addView(copyBeaconButton)
        column.addView(stageHelp)
        column.addView(label("Stage id (sublocation)"))
        column.addView(stageField)
        column.addView(stageStatus)
        column.addView(saveStageButton)

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

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 16, 0, 4)
    }

    private fun onSave() {
        val ssid = ssidField.text.toString().trim()
        val psk = pskField.text.toString()
        if (ssid.isBlank()) {
            Toast.makeText(this, "SSID required", Toast.LENGTH_SHORT).show()
            return
        }
        val creds = WifiCredentials(ssid = ssid, psk = psk)
        WifiCredentials.save(this, creds)
        WifiNetworkApplicator(this).apply(creds)
        Toast.makeText(this, "Saved. Approve the system notification to auto-connect.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun onClear() {
        WifiCredentials.clear(this)
        WifiNetworkApplicator(this).clear()
        ssidField.setText("")
        pskField.setText("")
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
    }

    private fun onCopyBeaconId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Beacon id", beaconId))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Stage save contract (SUBLOCATION_ADVERTISEMENT.md §4): trim → lowercase →
     * validate [a-z0-9-]{1,64}; empty clears back to the legacy layout; invalid
     * input is refused (never persisted, never hashed); any successful save
     * re-advertises immediately — no reboot.
     */
    private fun onSaveStage() {
        if (stageField.text.toString().isBlank()) {
            BeaconIdentity.saveStageId(this, null)
            stageField.setText("")
            val applied = MeshRouterService.INSTANCE?.restartBeaconAdvertising() ?: false
            updateStageStatus()
            Toast.makeText(
                this,
                if (applied) "Stage cleared — advertising legacy beacon layout"
                else "Stage cleared — legacy layout applies when the router service starts",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val normalized = BeaconIdentity.normalizeStageId(stageField.text.toString())
        if (normalized == null) {
            Toast.makeText(
                this,
                "Invalid stage id — 1-64 lowercase letters, digits, or hyphens",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        BeaconIdentity.saveStageId(this, normalized)
        stageField.setText(normalized)
        val applied = MeshRouterService.INSTANCE?.restartBeaconAdvertising() ?: false
        updateStageStatus()
        Toast.makeText(
            this,
            if (applied) "Stage saved — advertising restarted"
            else "Stage saved — applies when the router service starts",
            Toast.LENGTH_LONG
        ).show()
    }

    // Describes the CONFIGURED beacon layout (what the pref selects), not a live
    // on-air assertion — the mesh service may be stopped. onSaveStage's toast is
    // what confirms whether the change actually went on air.
    private fun updateStageStatus() {
        val stageId = BeaconIdentity.loadStageId(this)
        stageStatus.text = if (stageId == null) {
            "Beacon layout: legacy (no stage)"
        } else {
            val hashHex = BeaconIdentity.stageHash(stageId).joinToString("") { "%02x".format(it) }
            "Beacon layout: v1, stage \"$stageId\" (hash $hashHex)"
        }
    }
}
