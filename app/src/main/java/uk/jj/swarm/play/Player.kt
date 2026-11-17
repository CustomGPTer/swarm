package uk.jj.swarm.play

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import uk.jj.swarm.Db
import uk.jj.swarm.Prefs
import uk.jj.swarm.engine.Engine
import uk.jj.swarm.engine.StreamInfo
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lets the video player read a file that is still downloading. When the player asks for a part that has not
 * arrived yet, the read waits and tells the torrent engine to fetch those pieces first.
 */
@UnstableApi
class TorrentDataSource(private val info: StreamInfo) : BaseDataSource(true) {
    private val cache = uk.jj.swarm.engine.PieceCache.of(info)
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false
    private var lastPiece = -1

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        pos = dataSpec.position
        if (pos > info.size) throw IOException("Position past end of file")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, info.size - pos) else info.size - pos
        if (remaining > 0) waitPiece(((info.fileOffset + pos) / info.pieceLength).toInt())
        var waited = 0
        while (!info.file.exists()) { sleep(200); waited += 200; if (waited > 90000) throw IOException("The file has not started downloading yet") }
        raf = RandomAccessFile(info.file, "r")
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val abs = info.fileOffset + pos
        val piece = (abs / info.pieceLength).toInt()
        waitPiece(piece)
        // Each time the player moves into a new piece, ask for the ones after it, so it rarely has to stop and wait.
        if (piece != lastPiece) { lastPiece = piece; cache.want(piece + 1, info.window, info.lastPiece) }
        val pieceEnd = (piece + 1).toLong() * info.pieceLength - info.fileOffset
        val n = minOf(length.toLong(), remaining, pieceEnd - pos).toInt()
        val f = raf ?: throw IOException("closed")
        var r = -1; var tries = 0
        while (r <= 0) {
            f.seek(pos)
            r = f.read(buffer, offset, n)
            if (r <= 0) { if (++tries > 100) throw IOException("Could not read downloaded data"); sleep(100) }
        }
        pos += r; remaining -= r
        bytesTransferred(r)
        return r
    }

    private fun waitPiece(p: Int) {
        if (cache.has(p)) return
        cache.want(p, info.window, info.lastPiece)
        StreamStatus.waiting(info, p)
        try {
            var waited = 0
            while (!cache.has(p)) {
                sleep(250); waited += 250
                if (waited % 5000 == 0) cache.want(p, info.window, info.lastPiece)
                if (waited == 20000) cache.urgent(p)
                if (waited > 180000) throw IOException("Timed out waiting for this part of the video. Check seeders and connection.")
            }
        } finally { StreamStatus.arrived(info, p) }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { throw InterruptedIOException() } }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { raf?.close() } catch (e: Exception) { }
        raf = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** What the player is waiting for, so the screen can say so and not just spin. */
object StreamStatus {
    @Volatile private var info: StreamInfo? = null
    @Volatile private var piece = -1
    @Volatile private var since = 0L

    fun waiting(i: StreamInfo, p: Int) { info = i; piece = p; since = System.currentTimeMillis() }
    fun arrived(i: StreamInfo, p: Int) { if (piece == p) piece = -1 }
    fun clear() { piece = -1; info = null }

    /** Two short lines for the screen, or null when nothing is being waited for. */
    fun text(): String? {
        val i = info ?: return null
        val p = piece
        if (p < 0) return null
        val tailFrom = i.lastPiece - ((8L * 1024 * 1024) / i.pieceLength).toInt().coerceAtLeast(1)
        val what = when {
            p - i.firstPiece <= i.window -> "Getting the start of the video"
            p >= tailFrom -> "Getting the index at the end of the file"
            else -> "Waiting for the next part to download"
        }
        val t = Engine.torrents.value.firstOrNull { it.hash == i.hash }
        val secs = (System.currentTimeMillis() - since) / 1000
        val swarm = when {
            t == null -> ""
            t.state == uk.jj.swarm.engine.TState.PAUSED -> "The torrent is paused. Resume it to carry on."
            t.seeds + t.peers == 0 -> "Not connected to anyone sharing it right now"
            else -> "${t.seeds + t.peers} connected · ${uk.jj.swarm.Fmt.speed(t.dl.toLong())}"
        }
        return what + (if (secs >= 5) " (${secs}s)" else "…") + (if (swarm.isEmpty()) "" else "\n" + swarm)
    }
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var status: android.widget.TextView? = null
    private var streaming = false
    private var opened = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            val p = player
            val busy = p == null || p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE
            val t = if (!opened) "Opening…" else if (busy && streaming) StreamStatus.text() else null
            status?.let { v -> v.text = t ?: ""; v.visibility = if (t == null) android.view.View.GONE else android.view.View.VISIBLE }
            ui.postDelayed(this, 500)
        }
    }
    private var player: ExoPlayer? = null
    private var path = ""
    private var musicWasPlaying = false

    companion object {
        fun startUri(ctx: Context, uri: Uri) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("path", uri.toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

        @Volatile private var lastStart = 0L
        @Volatile private var pending: String? = null     // asked to open, not on screen yet
        @Volatile private var showing: String? = null     // on screen now

        fun start(ctx: Context, file: File, title: String, hash: String? = null, index: Int = -1) {
            // A second tap while the player is opening, or already showing the same file, must not open another one.
            val now = android.os.SystemClock.elapsedRealtime()
            val path = file.absolutePath
            if (showing == path || (pending == path && now - lastStart < 8000)) return
            lastStart = now; pending = path
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("path", path).putExtra("title", title).putExtra("hash", hash).putExtra("index", index)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val p = intent.getStringExtra("path") ?: return
        if (p == path) return            // the same video is already on screen
        setIntent(intent); recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        path = intent.getStringExtra("path") ?: run { finish(); return }
        val hash = intent.getStringExtra("hash")
        val index = intent.getIntExtra("index", -1)
        val file = File(path)
        val isContent = path.startsWith("content://")
        val mediaUri = if (isContent) Uri.parse(path) else Uri.fromFile(file)

        musicWasPlaying = Music.isPlaying()
        if (musicWasPlaying) Music.pause()

        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player = p
        val view = PlayerView(this)
        view.player = p
        view.setShowSubtitleButton(true)
        view.setShowNextButton(false); view.setShowPreviousButton(false)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        view.controllerShowTimeoutMs = 3500
        view.keepScreenOn = true
        setContentView(view)

        // Status line under the spinner: says what is being waited for.
        val st = android.widget.TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt()); text = "Opening…"
        }
        status = st
        val d = resources.displayMetrics.density
        addContentView(st, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER).apply { topMargin = (86 * d).toInt(); leftMargin = (24 * d).toInt(); rightMargin = (24 * d).toInt() })
        showing = path; if (pending == path) pending = null
        ui.post(statusTick)

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (isFinishing) return
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Can't play this video")
                    .setMessage((error.cause?.message ?: error.message ?: "Unknown error") + "\n\nYou can try another player app.")
                    .setPositiveButton("Open with…") { _, _ -> if (isContent) try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(mediaUri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { } else uk.jj.swarm.Actions.openExternal(this@PlayerActivity, file); finish() }
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { Db.get(this@PlayerActivity).savePosition(path, 0, p.duration.coerceAtLeast(0)); finish() }
            }
        })

        // Asking the engine about the file, and the database for the place last reached, can both take a moment
        // when the phone is busy. Neither may hold up the screen, so they run on their own thread.
        Thread({
            val stream = try { if (!isContent && hash != null && index >= 0 && !Engine.fileComplete(hash, index)) Engine.stream(hash, index) else null } catch (t: Throwable) { null }
            val subs = ArrayList<MediaItem.SubtitleConfiguration>()
            if (stream == null && !isContent) try {
                val base = file.nameWithoutExtension
                file.parentFile?.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(base) && f != file) {
                        val mime = when (f.extension.lowercase()) { "srt" -> MimeTypes.APPLICATION_SUBRIP; "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA; else -> null }
                        if (mime != null) subs += MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setMimeType(mime).setLabel(f.name.removePrefix(base).trim('.', ' ').ifBlank { "Subtitles" }).setSelectionFlags(0).build()
                    }
                }
            } catch (t: Throwable) { }
            val saved = try { Db.get(this).position(path) } catch (t: Throwable) { 0L }
            ui.post {
                if (isFinishing || isDestroyed || player !== p) return@post
                if (stream != null) {
                    streaming = true; Engine.streaming.incrementAndGet()
                    Toast.makeText(this, "Playing while it downloads. It may pause to wait for data.", Toast.LENGTH_LONG).show()
                    p.setMediaSource(ProgressiveMediaSource.Factory(DataSource.Factory { TorrentDataSource(stream) }).createMediaSource(MediaItem.fromUri(Uri.fromFile(file))))
                } else {
                    val b = MediaItem.Builder().setUri(mediaUri)
                    if (subs.isNotEmpty()) b.setSubtitleConfigurations(subs)
                    p.setMediaItem(b.build())
                }
                if (saved > 5000) p.seekTo(saved)
                p.prepare()
                p.playWhenReady = true
                opened = true
            }
        }, "swarm-player-open").start()
    }

    private fun savePos() {
        val p = player ?: return
        if (!opened) return
        val pos = p.currentPosition; val dur = p.duration
        val at = path; val app = applicationContext
        // The database can be busy. Never make the screen wait for it.
        Thread {
            try { if (dur > 0) Db.get(app).savePosition(at, if (pos > dur - 15000) 0 else pos, dur) else if (pos > 0) Db.get(app).savePosition(at, pos, 0) } catch (t: Throwable) { }
        }.start()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (e: Exception) { }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        (findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)?.let { v -> (v as? PlayerView)?.useController = !isInPictureInPictureMode }
    }

    override fun onStop() {
        super.onStop()
        savePos()
        if (!Prefs.bgVideo && !isInPictureInPictureMode) player?.pause()
        if (isInPictureInPictureMode.not() && isFinishing.not() && !Prefs.bgVideo) player?.pause()
    }

    override fun onDestroy() {
        savePos()
        ui.removeCallbacksAndMessages(null)
        if (showing == path) showing = null
        if (streaming) { streaming = false; Engine.streaming.decrementAndGet(); StreamStatus.clear() }
        player?.release(); player = null
        if (musicWasPlaying && Prefs.resumeMusic) Music.resume()
        super.onDestroy()
    }
}

data class Track(val title: String, val artist: String, val album: String, val path: String, val duration: Long)
data class MusicState(val title: String = "", val artist: String = "", val playing: Boolean = false, val active: Boolean = false, val position: Long = 0, val duration: Long = 0)

/** One music player for the whole app. The service only keeps it alive and shows the media notification. */
object Music {
    private var player: ExoPlayer? = null
    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    val state = MutableStateFlow(MusicState())

    fun get(ctx: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        p.addListener(object : Player.Listener {
            override fun onEvents(pl: Player, events: Player.Events) = publish()
        })
        player = p
        return p
    }

    fun publish() {
        val p = player ?: return
        val md = p.mediaMetadata
        state.value = MusicState(md.title?.toString() ?: "", md.artist?.toString() ?: "", p.isPlaying, p.mediaItemCount > 0, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0))
    }

    fun play(ctx: Context, tracks: List<Track>, index: Int) {
        val p = get(ctx)
        p.setMediaItems(tracks.map { t ->
            MediaItem.Builder().setUri(Uri.fromFile(File(t.path))).setMediaId(t.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build()
        }, index.coerceIn(0, maxOf(0, tracks.size - 1)), 0)
        p.prepare(); p.play()
        if (controller == null) try {
            controller = MediaController.Builder(ctx.applicationContext, SessionToken(ctx.applicationContext, ComponentName(ctx.applicationContext, MusicService::class.java))).buildAsync()
        } catch (e: Exception) { }
    }

    fun isPlaying() = player?.isPlaying == true
    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun toggle() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun prev() { player?.let { if (it.currentPosition > 4000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(ms: Long) { player?.seekTo(ms) }
    fun shuffle(on: Boolean) { player?.shuffleModeEnabled = on }
    fun stop() { player?.let { it.stop(); it.clearMediaItems() }; publish() }
}

class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Music.get(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }
}
