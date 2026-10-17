package app.deckline.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import androidx.media3.common.C

/** Owns the system equalizer attached to our player, and remembers its settings. */
object AudioFx {
    var eq: Equalizer? = null
        private set
    private var prefs: SharedPreferences? = null

    fun attach(context: Context, audioSessionId: Int) {
        release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        val p = context.applicationContext.getSharedPreferences("deckline", Context.MODE_PRIVATE)
        prefs = p
        eq = try {
            Equalizer(0, audioSessionId).also { e ->
                for (b in 0 until e.numberOfBands) {
                    val saved = p.getInt("band_$b", Int.MIN_VALUE)
                    if (saved != Int.MIN_VALUE) e.setBandLevel(b.toShort(), saved.toShort())
                }
                e.setEnabled(p.getBoolean("eq_on", false))
            }
        } catch (t: Throwable) {
            null // some devices don't offer an equalizer
        }
    }

    val enabled: Boolean get() = eq?.enabled == true

    fun setEnabled(on: Boolean) {
        try { eq?.setEnabled(on) } catch (_: Throwable) {}
        prefs?.edit()?.putBoolean("eq_on", on)?.apply()
    }

    fun setBand(band: Int, level: Int) {
        try { eq?.setBandLevel(band.toShort(), level.toShort()) } catch (_: Throwable) {}
        prefs?.edit()?.putInt("band_$band", level)?.apply()
    }

    fun bandLevel(band: Int): Int = try { eq?.getBandLevel(band.toShort())?.toInt() ?: 0 } catch (_: Throwable) { 0 }

    fun usePreset(index: Int) {
        val e = eq ?: return
        try { e.usePreset(index.toShort()) } catch (_: Throwable) { return }
        saveAll()
    }

    fun flat() {
        val e = eq ?: return
        for (b in 0 until e.numberOfBands) setBand(b, 0)
    }

    private fun saveAll() {
        val e = eq ?: return
        val ed = prefs?.edit() ?: return
        for (b in 0 until e.numberOfBands) ed.putInt("band_$b", bandLevel(b))
        ed.apply()
    }

    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        eq = null
    }
}
