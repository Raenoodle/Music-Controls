package dev.dariuss.musicbridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.service.notification.NotificationListenerService
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

const val ACTION_STATE = "dev.dariuss.musicbridge.STATE"
const val ACTION_COMMAND = "dev.dariuss.musicbridge.COMMAND"
const val SEND_PERMISSION = "dev.dariuss.musicbridge.permission.SEND_STATE"

/** Valenced first; plain Discord for root setups that keep its package name. */
private val TARGETS = listOf("app.valenced", "com.discord")
private const val ART_SIZE = 320

/**
 * Follows the current media session and sends what is playing to Discord.
 * Android only lets a notification listener read other apps' media sessions.
 */
class MediaListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: MediaListener? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val artLoader = Executors.newSingleThreadExecutor()
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null

    /** The same session through AndroidX, which knows shuffle and repeat. */
    private var compat: MediaControllerCompat? = null

    /** Track the art below belongs to. Art can arrive after the title, so it is sent whenever it changes. */
    private var artKey: String? = null
    private var art: ByteArray? = null
    private var artUrl: String? = null
    private var artSource: Any? = null
    private var artVersion = 0

    /** The art is only sent again when it changed, or when Discord asks for everything. */
    private var sentArtVersion = -1

    private val component get() = ComponentName(this, MediaListener::class.java)

    /** Commands from Music Controls. Discord cannot see this app, so they arrive as an implicit broadcast. */
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            command(intent.getStringExtra("command"), intent.getLongExtra("position", 0L))
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = broadcast()
        override fun onPlaybackStateChanged(state: PlaybackState?) = broadcast()
        override fun onSessionDestroyed() = refresh()
    }

    private val compatCallback = object : MediaControllerCompat.Callback() {
        override fun onShuffleModeChanged(shuffleMode: Int) = broadcast()
        override fun onRepeatModeChanged(repeatMode: Int) = broadcast()
        override fun onSessionReady() = broadcast()
    }

    override fun onListenerConnected() {
        instance = this
        val manager = getSystemService(MediaSessionManager::class.java)
        sessions = manager
        manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
        val filter = IntentFilter(ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, RECEIVER_EXPORTED)
        else registerReceiver(commands, filter)
        refresh()
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        runCatching { unregisterReceiver(commands) }
        use(null)
        KeepAlive.stop(this)
        instance = null
        // Some phones (Xiaomi, Huawei, Samsung with battery saving) disconnect listeners; ask to be bound again.
        runCatching { requestRebind(component) }
    }

    fun refresh() {
        val list = runCatching { sessions?.getActiveSessions(component) }.getOrNull().orEmpty()
        if (Log.isLoggable("MusicControls", Log.DEBUG)) {
            for (c in list) {
                val m = c.metadata
                Log.d(
                    "MusicControls",
                    "session ${c.packageName}: bitmap=${m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || m?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null} " +
                        "albumArtUri=${m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)} artUri=${m?.getString(MediaMetadata.METADATA_KEY_ART_URI)} " +
                        "iconUri=${m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)} mediaId=${m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)} mediaUri=${m?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)}",
                )
            }
        }
        pick(list)
    }

    /** The session that is playing wins; otherwise the one Android ranks first. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) use(best)
        broadcast()
    }

    private fun use(next: MediaController?) {
        controller?.unregisterCallback(callback)
        compat?.unregisterCallback(compatCallback)
        controller = next
        compat = next?.let {
            runCatching {
                MediaControllerCompat(this, MediaSessionCompat.Token.fromToken(it.sessionToken))
            }.getOrNull()
        }
        next?.registerCallback(callback, handler)
        compat?.registerCallback(compatCallback, handler)
        artKey = null
        if (next != null) KeepAlive.start(this) else KeepAlive.stop(this)
    }

    /** A custom action of the player whose name mentions [word], for players without the standard modes. */
    private fun customAction(word: String) = controller?.playbackState?.customActions?.firstOrNull {
        it.action.contains(word, ignoreCase = true) || it.name.toString().contains(word, ignoreCase = true)
    }

    private fun canShuffle() =
        (controller?.playbackState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE != 0L ||
            (compat?.shuffleMode ?: -1) >= 0

    private fun canRepeat() =
        (controller?.playbackState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SET_REPEAT_MODE != 0L ||
            (compat?.repeatMode ?: -1) >= 0

    fun command(name: String?, position: Long) {
        if (name == "request") {
            // Answered even when nothing plays, so Music Controls knows the bridge is there.
            sentArtVersion = -1
            refresh()
            return
        }
        val current = controller ?: return
        val controls = current.transportControls
        when (name) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "playPause" ->
                if (current.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause()
                else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "seek" -> controls.seekTo(position)
            "shuffle" -> {
                val custom = customAction("shuffle")
                if (canShuffle() || custom == null) {
                    val on = compat?.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    compat?.transportControls?.setShuffleMode(
                        if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL,
                    )
                } else {
                    controls.sendCustomAction(custom, null)
                }
            }
            "repeat" -> {
                val custom = customAction("repeat")
                if (canRepeat() || custom == null) {
                    // Off, then all, then one, like most players.
                    val next = when (compat?.repeatMode) {
                        PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP ->
                            PlaybackStateCompat.REPEAT_MODE_ONE
                        PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_NONE
                        else -> PlaybackStateCompat.REPEAT_MODE_ALL
                    }
                    compat?.transportControls?.setRepeatMode(next)
                } else {
                    controls.sendCustomAction(custom, null)
                }
            }
        }
    }

    private fun broadcast() {
        val intent = Intent(ACTION_STATE).putExtra("access", true)
        val current = controller
        if (current == null) {
            send(intent.putExtra("active", false))
            return
        }
        val metadata = current.metadata
        val state = current.playbackState
        val title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = metadata?.text(MediaMetadata.METADATA_KEY_ALBUM)
        val shuffleMode = compat?.shuffleMode ?: -1
        val repeatMode = compat?.repeatMode ?: -1

        val key = listOf(current.packageName, title, artist, album).joinToString("|")
        if (key != artKey) {
            artKey = key
            art = null
            artUrl = null
            artSource = null
            artVersion++
        }
        updateArt(metadata, key)

        intent
            .putExtra("active", true)
            .putExtra("package", current.packageName)
            .putExtra("app", appLabel(current.packageName))
            .putExtra("title", title)
            .putExtra("artist", artist)
            .putExtra("album", album)
            .putExtra("duration", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .putExtra("playing", state?.state == PlaybackState.STATE_PLAYING)
            .putExtra("position", state?.position ?: 0L)
            .putExtra("updated", state?.lastPositionUpdateTime ?: 0L)
            .putExtra("speed", state?.playbackSpeed ?: 1f)
            .putExtra("actions", state?.actions ?: 0L)
            .putExtra("canShuffle", canShuffle() || customAction("shuffle") != null)
            .putExtra("canRepeat", canRepeat() || customAction("repeat") != null)
            .putExtra(
                "shuffle",
                shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                    shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP,
            )
            .putExtra(
                "repeat",
                when (repeatMode) {
                    PlaybackStateCompat.REPEAT_MODE_ONE -> "one"
                    PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> "all"
                    else -> "off"
                },
            )
            .putExtra("artKey", key)
            .putExtra("artVersion", artVersion)
        artUrl?.let { intent.putExtra("artUrl", it) }
        if (artVersion != sentArtVersion) {
            art?.let { intent.putExtra("art", it) }
            sentArtVersion = artVersion
        }
        send(intent)
    }

    /**
     * Players give the cover as a bitmap or as a URI, sometimes only in a later update (SoundCloud).
     * Bitmaps are compressed here; content URIs are read off the main thread; web URLs are passed on.
     */
    private fun updateArt(metadata: MediaMetadata?, key: String) {
        if (Log.isLoggable("MusicControls", Log.DEBUG)) {
            Log.d("MusicControls", "art for $key: bitmap=${metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null} uri=${metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)}")
        }
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            if (bitmap === artSource) return
            artSource = bitmap
            compress(bitmap)?.let {
                art = it
                artVersion++
            }
            return
        }
        val uri = metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.text(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return
        if (uri == artSource) return
        artSource = uri
        val web = webUrlOf(uri)
        if (web != null) {
            artUrl = web
            artVersion++
            return
        }
        artLoader.execute {
            val bytes = runCatching {
                contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
            }.getOrNull()?.let(::compress)
            if (bytes == null) {
                // Try again on the next update instead of keeping the song without a cover.
                handler.post { if (artSource == uri) artSource = null }
                return@execute
            }
            handler.post {
                if (artKey != key) return@post
                art = bytes
                artVersion++
                broadcast()
            }
        }
    }

    /**
     * A web address for the cover when the player has one. SoundCloud wraps its covers in its own
     * image provider (content://com.soundcloud.android.imageProvider/artworks-….png?_HOST_=i1.sndcdn.com&_PROTOCOL_=https),
     * which downloads on demand and sometimes fails, so the real address is rebuilt from it.
     */
    private fun webUrlOf(uri: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val parsed = runCatching { Uri.parse(uri.replace("&amp;", "&")) }.getOrNull() ?: return null
        val host = runCatching { parsed.getQueryParameter("_HOST_") }.getOrNull() ?: return null
        val protocol = runCatching { parsed.getQueryParameter("_PROTOCOL_") }.getOrNull() ?: "https"
        val path = parsed.encodedPath ?: return null
        return "$protocol://$host$path"
    }

    /** Sends to each Discord install only, behind the signature permission. */
    private fun send(intent: Intent) {
        for (target in TARGETS) {
            val installed = runCatching { packageManager.getPackageInfo(target, 0) }.isSuccess
            if (!installed) continue
            runCatching {
                sendBroadcast(Intent(intent).setPackage(target).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null)
            }
        }
    }

    private fun MediaMetadata.text(key: String) = getString(key)?.takeIf { it.isNotBlank() }

    private fun appLabel(packageName: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun compress(bitmap: Bitmap): ByteArray? = runCatching {
        val scale = ART_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }.getOrNull()
}
