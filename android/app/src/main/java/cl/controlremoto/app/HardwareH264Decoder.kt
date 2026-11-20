package cl.controlremoto.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * H.264 Annex B access-unit decoder using Android's hardware-backed MediaCodec.
 * Codec creation occurs when a Surface is ready, not on the OkHttp network thread.
 * Decode backpressure drops new frames rather than building unlimited latency.
 * Requires SPS/PPS in the H.264 stream after Surface recreation (keyframe).
 */
class HardwareH264Decoder {
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "rcp-h264-decode").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    @Volatile private var currentSurface: Surface? = null
    private var codec: MediaCodec? = null
    @Volatile var active = false
        private set
    @Volatile var videoWidth: Int = 1920
        private set
    @Volatile var videoHeight: Int = 1080
        private set

    fun attachSurface(surface: Surface) {
        worker.execute {
            // Destroy only the old decoder instance; keep the websocket alive on rotation.
            releaseDecoder()
            if (!surface.isValid) return@execute
            configureDecoder(surface)
        }
    }

    fun detachSurface(surface: Surface) {
        worker.execute {
            if (currentSurface === surface) releaseDecoder()
        }
    }

    fun pushAccessUnit(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return
        // At most 2 pending AUs. Latency wins over a larger playback queue.
        if (pending.incrementAndGet() > 2) {
            pending.decrementAndGet()
            return
        }
        worker.execute {
            try {
                val decoder = codec ?: return@execute
                var index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val input = decoder.getInputBuffer(index)
                    if (input != null && bytes.size <= input.capacity()) {
                        input.clear()
                        input.put(bytes)
                        decoder.queueInputBuffer(index, 0, bytes.size, SystemClock.elapsedRealtimeNanos() / 1000, 0)
                    } else {
                        decoder.queueInputBuffer(index, 0, 0, 0, 0)
                    }
                }
                val info = MediaCodec.BufferInfo()
                for (i in 0 until 8) {
                    index = decoder.dequeueOutputBuffer(info, 0)
                    if (index >= 0) decoder.releaseOutputBuffer(index, true)
                    else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        videoWidth = output.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = output.getInteger(MediaFormat.KEY_HEIGHT)
                    } else break
                }
            } catch (_: Exception) {
                releaseDecoder()
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun restart() {
        worker.execute {
            val oldSurface = currentSurface
            releaseDecoder()
            if (oldSurface != null && oldSurface.isValid) configureDecoder(oldSurface)
        }
    }

    fun reset() { worker.execute { releaseDecoder() } }

    private fun configureDecoder(surface: Surface) {
        currentSurface = surface
        try {
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            active = true
        } catch (_: Exception) { releaseDecoder() }
    }

    fun close() {
        worker.execute { releaseDecoder() }
        // Do not shutdown immediately: ensure release runs before thread termination.
        worker.shutdown()
    }

    private fun releaseDecoder() {
        active = false
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        currentSurface = null
    }
}
