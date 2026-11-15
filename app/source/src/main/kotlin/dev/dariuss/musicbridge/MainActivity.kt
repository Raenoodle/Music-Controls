package dev.dariuss.musicbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One screen: whether notification access is on, and shortcuts to the settings it needs. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, size: Float, gap: Int = padding / 2) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 0, 0, gap)
        }
        fun button(label: String, open: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { runCatching(open) }
        }

        column.addView(text("Music Controls", 26f))
        column.addView(
            text(
                "Companion app for the Music Controls plugin in Discord (Valenced). It lets the plugin show and " +
                    "control what YouTube Music, SoundCloud and other players are playing. Spotify does not need it. " +
                    "Android only.",
                15f,
            ),
        )
        column.addView(
            text(
                "It needs notification access to read media sessions. It does not read, keep or send your " +
                    "notifications, and it has no internet access.",
                14f,
            ),
        )
        status = text("", 17f)
        column.addView(status)

        column.addView(button("Open notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(
            text(
                "Android 13 and newer block this for apps installed from a file. If Music Controls is greyed out, " +
                    "open App info below, tap ⋮ and choose \"Allow restricted settings\", then try again.",
                14f,
                padding / 4,
            ).apply { setPadding(0, padding / 2, 0, padding / 4) },
        )
        column.addView(button("Open App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        column.addView(
            text(
                "If the player in Discord stops updating after a while, your phone is closing this app to save " +
                    "battery. Set Music Controls to \"Unrestricted\" or \"Don't optimize\" (on Xiaomi also allow Autostart).",
                14f,
                padding / 4,
            ).apply { setPadding(0, padding / 2, 0, padding / 4) },
        )
        column.addView(button("Open battery settings") {
            val intent = if (Build.VERSION.SDK_INT >= 23) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            startActivity(intent)
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAccess()
        status.text = if (granted) {
            "✓ Notification access is on. You can close this app."
        } else {
            "✗ Notification access is off."
        }
        if (granted) {
            val listener = MediaListener.instance
            if (listener != null) listener.refresh()
            // Granted but not bound, for example after the phone closed the app: ask Android to bind it again.
            else runCatching {
                NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java))
            }
        }
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val mine = ComponentName(this, MediaListener::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == mine }
    }
}
