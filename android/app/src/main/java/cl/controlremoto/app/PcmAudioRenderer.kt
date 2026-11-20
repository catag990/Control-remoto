package cl.controlremoto.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Low-CPU stereo PCM playback for WASAPI loopback packets (RCA3).
 * Uses a bounded queue and a dedicated audio thread: network/video callbacks
 * never wait for AudioTrack and stale audio is dropped instead of adding lag.
 * Microphone is neither requested nor recorded.
 */
class PcmAudioRenderer {
    private val gate = Any()
    private val packets = ArrayBlockingQueue<ByteArray>(10)
    private var output: AudioTrack? = null
    @Volatile private var running = true
    @Volatile private var enabled = true
    @Volatile private var volume = 1f
    @Volatile var status = "Sin audio"
        private set
    private val player = Thread({
        while (running) {
            try {
                val packet = packets.poll(150, TimeUnit.MILLISECONDS) ?: continue
                synchronized(gate) {
                    val track = output
                    if (enabled && track != null && packet.isNotEmpty()) {
                        // This thread may block briefly; packet queue is bounded.
                        track.write(packet, 0, packet.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
            } catch (_: InterruptedException) { break }
            catch (_: IllegalStateException) { status = "Salida de audio interrumpida" }
        }
    }, "rcp-pcm-playback").apply { isDaemon = true; start() }

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate !in 8000..192000 || channels != 2) return
        synchronized(gate) {
            packets.clear()
            output?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) { } }
            output = null
            val minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) { status = "Formato de audio no admitido"; return }
            try {
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 / 8)).build()
                if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); status = "Salida no disponible"; return }
                track.setVolume(volume)
                if (enabled) track.play()
                output = track
                status = "Audio del PC: $sampleRate Hz, estéreo"
            } catch (_: Exception) { status = "Error de AudioTrack" }
        }
    }

    fun push(stereoPcm16: ByteArray) {
        if (!running || !enabled || stereoPcm16.size > 512_000) return
        if (!packets.offer(stereoPcm16)) {
            packets.poll()
            packets.offer(stereoPcm16)
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        synchronized(gate) {
            if (!value) {
                packets.clear()
                try { output?.pause(); output?.flush() } catch (_: Exception) { }
            } else {
                try { output?.play() } catch (_: Exception) { }
            }
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        synchronized(gate) { try { output?.setVolume(volume) } catch (_: Exception) { } }
    }

    fun reset() {
        synchronized(gate) {
            packets.clear()
            try { output?.pause(); output?.flush(); output?.release() } catch (_: Exception) { }
            output = null
            status = "Sin audio"
        }
    }

    fun close() {
        running = false
        player.interrupt()
        reset()
    }
}
