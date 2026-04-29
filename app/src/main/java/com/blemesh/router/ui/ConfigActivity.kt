package com.blemesh.router.ui

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
    }

    override fun onResume() {
        super.onResume()
        val saved = WifiCredentials.load(this)
        ssidField.setText(saved.ssid)
        pskField.setText(saved.psk)
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
}
