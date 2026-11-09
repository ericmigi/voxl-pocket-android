package com.tembo.voxl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One horizontal band of the desktop with its own H.264 stream (port of web/src/worker.ts),
 * decoded by its own MediaCodec straight into the band's surface. decode() runs on the
 * socket thread, codec callbacks on [decodeThread], setSurface() on the main thread.
 */
class Stripe(val y: Int, val height: Int, val width: Int) {
    @Volatile var waitingKey = true
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var failed = false
    private val pending = ArrayDeque<ByteArray>()
    private val free = ArrayDeque<Int>()
    private var pts = 0L

    @Synchronized fun setSurface(s: Surface?) {
        reset()
        surface?.release()
        surface = s
    }

    /** Queues one Annex-B access unit. Returns false when the decoder needs a keyframe. */
    @Synchronized fun decode(data: ByteArray, key: Boolean): Boolean {
        if (failed) reset()
        val out = surface ?: return false
        if (codec == null) {
            if (!key) return false
            codec = runCatching { start(out) }.getOrNull() ?: return false
        }
        // A decoder that stopped taking input would buffer forever; start over from a keyframe.
        if (pending.size > 30) { reset(); return false }
        pending.add(data)
        feed()
        return !failed
    }

    private fun start(out: Surface): MediaCodec {
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 1 shl 20))
        f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(m: MediaCodec, i: Int) = synchronized(this@Stripe) {
                if (m === codec) { free.add(i); feed() }
            }
            override fun onOutputBufferAvailable(m: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                synchronized(this@Stripe) { if (m === codec) runCatching { m.releaseOutputBuffer(i, true) } }
            }
            override fun onError(m: MediaCodec, e: MediaCodec.CodecException) = synchronized(this@Stripe) {
                if (m === codec) failed = true
            }
            override fun onOutputFormatChanged(m: MediaCodec, f: MediaFormat) {}
        }, decodeHandler)
        try {
            c.configure(f, out, null, 0)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    private fun feed() {
        val c = codec ?: return
        try {
            while (pending.isNotEmpty() && free.isNotEmpty()) {
                val i = free.poll()!!
                val d = pending.poll()!!
                val buf = c.getInputBuffer(i)!!
                if (d.size > buf.capacity()) { failed = true; return }
                buf.clear(); buf.put(d)
                c.queueInputBuffer(i, 0, d.size, pts, 0)
                pts += 16_666
            }
        } catch (e: Exception) {
            failed = true
        }
    }

    @Synchronized fun reset() {
        waitingKey = true
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        failed = false
        pending.clear()
        free.clear()
    }

    companion object {
        private val decodeThread = HandlerThread("voxl-decode").apply { start() }
        val decodeHandler = Handler(decodeThread.looper)
    }
}

/** WebSocket connection to the voxl host. Listener calls arrive on the main thread. */
class Session(private val ctx: Context, val host: Host, private val listener: Listener) {
    interface Listener {
        /** `displays`/`display`: how many displays the host can stream and which one this is. */
        fun onHello(width: Int, height: Int, stripes: List<Stripe>, resizeScale: Double?, displays: Int, display: Int)
        fun onCursor(image: Bitmap?, hotX: Int, hotY: Int)
        fun onState(text: String?)
        /** The host refused the saved password, which is now forgotten. */
        fun onAuthFailed()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false; private set
    @Volatile private var stripes: List<Stripe> = emptyList()
    private val pending = ConcurrentHashMap<Int, Int>() // frame id -> parts still to arrive
    private var lastKeyRequest = 0L
    private var keyRetryPosted = false
    private var stopped = true
    private val clipboard = ctx.getSystemService(ClipboardManager::class.java)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { sendClipboard() }
    private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

    init { clipboard.addPrimaryClipChangedListener(clipListener) }

    /**
     * Timestamp of the clip already in sync with a host (set or sent by us). Kept across
     * launches, so a copy made before connecting is sent, but an old one isn't re-sent.
     * Comparing the description's timestamp means the clip is only read when it is new.
     */
    private var clipSeen: Long
        get() = prefs.getLong("clipSynced", -1)
        set(v) { prefs.edit().putLong("clipSynced", v).apply() }

    /** Sends the device clipboard to the host if it changed since we last synced. Main thread. */
    fun sendClipboard() {
        val w = ws
        if (!connected || w == null) return
        val desc = clipboard.primaryClipDescription ?: return
        val ts = desc.timestamp
        if (ts == clipSeen) return
        if (desc.extras?.getBoolean(FROM_HOST) == true) { clipSeen = ts; return }
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
        val text = item.text?.toString()
        // Marked synced only once queued, so a failed send is retried on the next V.
        val sent = if (!text.isNullOrEmpty()) {
            w.send("c,$text")
        } else {
            // ponytail: reads and re-encodes the image on the main thread; move off it if big pastes stutter.
            val png = item.uri?.let { readPng(it, desc.hasMimeType("image/png")) } ?: return
            if (png.size >= 15 shl 20) return
            w.send((byteArrayOf(0x11) + png).toByteString())
        }
        if (sent) clipSeen = ts
    }

    private fun readPng(uri: android.net.Uri, isPng: Boolean): ByteArray? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (isPng) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    fun start() {
        stopped = false
        if (ws != null) return
        val pw = Hosts.password(ctx, host.url)
        if (pw == null) { listener.onState("No password saved"); return }
        val req = runCatching {
            Request.Builder().url(host.url).header("Authorization", "Bearer $pw").build()
        }.getOrElse { listener.onState("Bad URL"); return }
        listener.onState("Connecting…")
        ws = http.newWebSocket(req, Socket())
    }

    fun stop() {
        stopped = true
        ws?.cancel()
        dropped(retry = false)
    }

    /** Ends the session for good. */
    fun close() {
        stop()
        clipboard.removePrimaryClipChangedListener(clipListener)
        stripes.forEach { it.setSurface(null) }
        stripes = emptyList()
    }

    /** Returns whether the message was queued (false while disconnected). */
    fun send(s: String): Boolean = connected && ws?.send(s) == true

    private fun dropped(retry: Boolean) {
        ws = null
        connected = false
        pending.clear()
        stripes.forEach { it.reset() }
        if (!retry || stopped) return
        listener.onState("Reconnecting…")
        main.postDelayed({ if (!stopped) start() }, 1000)
    }

    private inner class Socket : WebSocketListener() {
        private fun mine(w: WebSocket) = w === ws

        override fun onOpen(w: WebSocket, r: Response) {
            if (!mine(w)) return
            connected = true
            w.send("v,1")
            w.send("au,0")
        }

        override fun onMessage(w: WebSocket, text: String) { if (mine(w)) text(w, text) }
        override fun onMessage(w: WebSocket, bytes: ByteString) { if (mine(w)) binary(w, bytes.toByteArray()) }
        override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null) }

        override fun onClosed(w: WebSocket, code: Int, reason: String) {
            main.post { if (mine(w)) dropped(retry = true) }
        }

        override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
            val code = r?.code
            main.post {
                if (!mine(w)) return@post
                if (code == 401 || code == 403) {
                    Hosts.setPassword(ctx, host.url, null)
                    stop()
                    listener.onState("Wrong password")
                    listener.onAuthFailed()
                } else {
                    dropped(retry = true)
                }
            }
        }
    }

    /** Runs on the main thread, unless the connection `w` was stopped or replaced meanwhile. */
    private fun post(w: WebSocket, f: () -> Unit) = main.post { if (w === ws) f() }

    private fun text(sock: WebSocket, s: String) {
        val j = runCatching { JSONObject(s) }.getOrNull() ?: return
        when (j.optString("t")) {
            "hello" -> {
                val w = j.optInt("w"); val h = j.optInt("h")
                val bands = j.optJSONArray("stripes")
                val next = if (bands == null || bands.length() == 0) listOf(Stripe(0, h, w)) else
                    List(bands.length()) { bands.getJSONArray(it).let { b -> Stripe(b.getInt(0), b.getInt(1), w) } }
                val old = stripes
                stripes = next
                pending.clear()
                val scale = j.optJSONObject("resize")?.optDouble("scale")?.takeIf { !it.isNaN() }
                val displays = j.optInt("displays", 1); val display = j.optInt("display", 0)
                main.post {
                    old.forEach { it.setSurface(null) }
                    if (sock !== ws) return@post
                    listener.onState(null)
                    listener.onHello(w, h, next, scale, displays, display)
                    sendClipboard() // anything copied on the device while disconnected
                }
            }
            "ping" -> j.opt("s")?.let { send("po,$it") }
            "clip" -> if (j.has("d")) {
                val d = j.optString("d")
                post(sock) {
                    clipboard.setPrimaryClip(fromHost(ClipData.newPlainText("voxl", d)))
                }
            }
            "close" -> post(sock) {
                stop()
                listener.onState("Closed by host. Tap to reconnect.")
            }
        }
    }

    private fun binary(sock: WebSocket, d: ByteArray) {
        if (d.isEmpty()) return
        val u8 = { i: Int -> d[i].toInt() and 0xff }
        when (d[0].toInt()) {
            0x01 -> {
                if (d.size < 16) return
                val key = u8(1) and 1 == 1
                val frame = u8(2) shl 24 or (u8(3) shl 16) or (u8(4) shl 8) or u8(5)
                val idx = u8(10); val parts = maxOf(u8(12), 1)
                stripes.getOrNull(idx)?.let { st ->
                    if (key) st.waitingKey = false
                    if (!st.waitingKey && !st.decode(d.copyOfRange(16, d.size), key)) st.waitingKey = true
                    if (st.waitingKey) requestKeyframe()
                }
                // A frame is acked once every stripe part arrived, decoded or not (same as the web worker).
                val left = (pending[frame] ?: parts) - 1
                if (left <= 0) { pending.remove(frame); send("a,${frame.toUInt()}") } else pending[frame] = left
            }
            0x03 -> {
                if (d.size < 14) return
                val u16 = { o: Int -> u8(o) shl 8 or u8(o + 1) }
                val w = u16(2); val h = u16(4); val hx = u16(6); val hy = u16(8)
                val bmp = if (w > 0 && h > 0 && d.size >= 14 + w * h * 4) {
                    // RGBA, not premultiplied; createBitmap premultiplies ARGB ints.
                    val px = IntArray(w * h) { i ->
                        val o = 14 + i * 4
                        u8(o + 3) shl 24 or (u8(o) shl 16) or (u8(o + 1) shl 8) or u8(o + 2)
                    }
                    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                } else null
                post(sock) { listener.onCursor(bmp, hx, hy) }
            }
            0x04 -> if (d.size > 1) post(sock) { setClipboardImage(d.copyOfRange(1, d.size)) } // image copied on the host
        }
    }

    private fun setClipboardImage(png: ByteArray) {
        runCatching {
            // A new file per image: an app holding an older clip's URI must not read a newer image.
            // ponytail: keeps the last 5; older clips in clipboard history stop resolving.
            val dir = File(ctx.cacheDir, "clip").apply { mkdirs() }
            val f = File.createTempFile("clip-", ".png", dir).apply { writeBytes(png) } // unique name
            val uri = FileProvider.getUriForFile(ctx, "com.tembo.voxl.clip", f)
            clipboard.setPrimaryClip(fromHost(ClipData.newUri(ctx.contentResolver, "voxl image", uri)))
            // Pruned only once the new image is published; the oldest go first.
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }
        }
    }

    /**
     * Asks the host for keyframes, at most every 500 ms. A request inside that window is
     * sent when it ends, so a stripe that only became ready meanwhile is not left blank
     * on a desktop that never changes. Any thread.
     */
    @Synchronized fun requestKeyframe() {
        val wait = lastKeyRequest + 500 - SystemClock.elapsedRealtime()
        if (wait > 0) {
            if (!keyRetryPosted) {
                keyRetryPosted = true
                main.postDelayed({
                    synchronized(this) { keyRetryPosted = false }
                    if (stripes.any { it.waitingKey }) requestKeyframe()
                }, wait)
            }
            return
        }
        lastKeyRequest = SystemClock.elapsedRealtime()
        send("kf")
    }

    companion object {
        /**
         * Marks a clip set from the host, so it is never sent back. Its timestamp can't be
         * recorded instead: reading the clipboard without window focus returns nothing.
         */
        private const val FROM_HOST = "voxl.fromHost"
        private fun fromHost(c: ClipData) = c.apply {
            description.extras = android.os.PersistableBundle().apply { putBoolean(FROM_HOST, true) }
        }

        private val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // the host pings; a quiet desktop is not a dead one
            .build()
    }
}
