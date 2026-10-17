package app.deckline.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Segmented spectrum analyzer and oscilloscope, drawn like a glowing hi-fi display. */
class SpectrumView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var active = false
        set(value) { field = value; postInvalidateOnAnimation() }
    var mode = 0
        private set
    var onModeChange: ((Int) -> Unit)? = null

    private val n = SpectrumTap.N
    private val samples = FloatArray(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val bars = 24
    private val levels = FloatArray(bars)
    private val peaks = FloatArray(bars)
    private val density = resources.displayMetrics.density

    private val lit = context.getColor(R.color.vfd)
    private val dim = context.getColor(R.color.vfd_dim)
    private val warn = context.getColor(R.color.vfd_warn)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = lit
    }
    private val path = Path()

    init {
        isClickable = true
        setOnClickListener {
            mode = (mode + 1) % 2
            onModeChange?.invoke(mode)
            invalidate()
        }
    }

    fun setModeQuietly(m: Int) { mode = m.coerceIn(0, 1); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fresh = active && SystemClock.uptimeMillis() - SpectrumTap.lastWrite < 400
        if (fresh) SpectrumTap.snapshot(samples)
        if (mode == 0) drawBars(canvas, fresh) else drawScope(canvas, fresh)
        if (active || levels.any { it > 0.01f } || peaks.any { it > 0.01f }) postInvalidateOnAnimation()
    }

    private fun drawBars(canvas: Canvas, fresh: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val target = FloatArray(bars)
        if (fresh) {
            for (i in 0 until n) { re[i] = samples[i] * window[i]; im[i] = 0f }
            fft(re, im)
            val rate = SpectrumTap.sampleRate.toFloat()
            val half = n / 2
            for (b in 0 until bars) {
                val lo = 40f * (16000f / 40f).pow(b.toFloat() / bars)
                val hi = 40f * (16000f / 40f).pow((b + 1).toFloat() / bars)
                val i0 = (lo / rate * n).toInt().coerceIn(1, half - 1)
                val i1 = max(i0 + 1, (hi / rate * n).toInt()).coerceAtMost(half)
                var m = 0f
                for (i in i0 until i1) m = max(m, sqrt(re[i] * re[i] + im[i] * im[i]))
                val db = 20f * log10(m / (n / 4f) + 1e-9f)
                target[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
            }
        }
        for (b in 0 until bars) {
            levels[b] = max(target[b], levels[b] - 0.035f)
            peaks[b] = max(peaks[b] - 0.01f, levels[b])
        }

        val gap = w * 0.008f
        val bw = (w - gap * (bars - 1)) / bars
        val cellH = max(3 * density, h / 14f)
        val cells = (h / cellH).toInt()
        for (b in 0 until bars) {
            val x = b * (bw + gap)
            val on = (levels[b] * cells).roundToInt()
            for (c in 0 until cells) {
                val y = h - (c + 1) * cellH
                if (c < on) {
                    fill.color = if (c > cells * 0.8f) warn else lit
                    fill.alpha = 255
                } else {
                    fill.color = dim
                    fill.alpha = 90
                }
                canvas.drawRect(x, y + density * 0.6f, x + bw, y + cellH - density * 0.6f, fill)
            }
            if (peaks[b] > 0.02f) {
                fill.color = lit; fill.alpha = 255
                val py = h - peaks[b] * h
                canvas.drawRect(x, py, x + bw, py + 2 * density, fill)
            }
        }
    }

    private fun drawScope(canvas: Canvas, fresh: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        if (fresh) {
            for (i in 0 until n) {
                val x = i * w / (n - 1)
                val y = h / 2 - samples[i].coerceIn(-1f, 1f) * h / 2
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        } else {
            path.moveTo(0f, h / 2); path.lineTo(w, h / 2)
        }
        canvas.drawPath(path, line)
        for (b in 0 until bars) { levels[b] = 0f; peaks[b] = 0f }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
