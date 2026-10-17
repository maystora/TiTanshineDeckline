package app.deckline.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Keeps the most recent slice of the decoded audio for the visualizer to read. */
object SpectrumTap : TeeAudioProcessor.AudioBufferSink {
    const val N = 1024
    private val ring = FloatArray(N)
    @Volatile private var pos = 0
    @Volatile var sampleRate = 44100
        private set
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    @Volatile var lastWrite = 0L
        private set

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val ch = channels
        if (encoding != C.ENCODING_PCM_16BIT || ch <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = b.remaining() / (2 * ch)
        var p = pos
        val scale = 1f / (ch * 32768f)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until ch) s += b.short.toFloat()
            ring[p] = s * scale
            p = (p + 1) and (N - 1)
        }
        pos = p
        lastWrite = SystemClock.uptimeMillis()
    }

    /** Copies the latest N samples, oldest first. */
    fun snapshot(out: FloatArray) {
        val p = pos
        for (i in 0 until N) out[i] = ring[(p + i) and (N - 1)]
    }
}
