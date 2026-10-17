package app.deckline.player

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlin.math.pow

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))
                .build()
        )
        .build()
}

class MainActivity : Activity() {

    private lateinit var countText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var flagPlay: TextView
    private lateinit var flagShuf: TextView
    private lateinit var flagRep: TextView
    private lateinit var flagEq: TextView
    private lateinit var trackNo: TextView
    private lateinit var vizMode: TextView
    private lateinit var durText: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: ImageButton
    private lateinit var shuffleChip: TextView
    private lateinit var repeatChip: TextView
    private lateinit var volume: SeekBar
    private lateinit var tabLib: TextView
    private lateinit var tabEq: TextView
    private lateinit var libPanel: View
    private lateinit var eqPanel: View
    private lateinit var eqContainer: LinearLayout
    private lateinit var search: EditText
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: TextView
    private lateinit var list: ListView

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var all: List<Track> = emptyList()
    private var shown: List<Track> = emptyList()
    private var queueIds: List<Long> = emptyList()
    private val adapter = TrackAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dragging = false
    private var showRemain = false
    private val prefs by lazy { getSharedPreferences("deckline", MODE_PRIVATE) }

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireControls()
        showTab(eq = false)
        list.adapter = adapter
        if (!hasPermission()) showNeedsPermission() // the library loads in onResume once allowed
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (t: Throwable) { null } ?: return@addListener
            controller = c
            c.addListener(playerListener)
            c.volume = volumeCurve(prefs.getInt("volume", 85))
            refresh()
            buildEqualizer()
        }, ContextCompat.getMainExecutor(this))
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        spectrum.active = false
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------------- views ----------------

    private fun bindViews() {
        countText = findViewById(R.id.countText)
        timeText = findViewById(R.id.timeText)
        titleText = findViewById(R.id.titleText)
        flagPlay = findViewById(R.id.flagPlay)
        flagShuf = findViewById(R.id.flagShuf)
        flagRep = findViewById(R.id.flagRep)
        flagEq = findViewById(R.id.flagEq)
        trackNo = findViewById(R.id.trackNo)
        vizMode = findViewById(R.id.vizMode)
        durText = findViewById(R.id.durText)
        spectrum = findViewById(R.id.spectrum)
        seek = findViewById(R.id.seek)
        playBtn = findViewById(R.id.playBtn)
        shuffleChip = findViewById(R.id.shuffleChip)
        repeatChip = findViewById(R.id.repeatChip)
        volume = findViewById(R.id.volume)
        tabLib = findViewById(R.id.tabLib)
        tabEq = findViewById(R.id.tabEq)
        libPanel = findViewById(R.id.libPanel)
        eqPanel = findViewById(R.id.eqPanel)
        eqContainer = findViewById(R.id.eqContainer)
        search = findViewById(R.id.search)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        allowBtn = findViewById(R.id.allowBtn)
        list = findViewById(R.id.list)
        titleText.isSelected = true // lets the long title scroll
    }

    private fun wireControls() {
        playBtn.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.prevBtn).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.nextBtn).setOnClickListener { controller?.seekToNextMediaItem() }
        findViewById<View>(R.id.stopBtn).setOnClickListener {
            controller?.let { it.pause(); it.seekTo(0) }
        }
        timeText.setOnClickListener { showRemain = !showRemain; updateTime() }

        shuffleChip.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        repeatChip.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }

        volume.progress = prefs.getInt("volume", 85)
        volume.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                controller?.volume = volumeCurve(p)
                prefs.edit().putInt("volume", p).apply()
            }
        })

        seek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val c = controller ?: return
                val d = c.duration
                if (d > 0) c.seekTo(sb.progress * d / 1000)
            }
        })

        spectrum.setModeQuietly(prefs.getInt("viz", 0))
        vizMode.text = if (spectrum.mode == 0) "Spectrum" else "Scope"
        spectrum.onModeChange = { m ->
            vizMode.text = if (m == 0) "Spectrum" else "Scope"
            prefs.edit().putInt("viz", m).apply()
        }

        tabLib.setOnClickListener { showTab(eq = false) }
        tabEq.setOnClickListener { showTab(eq = true) }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        list.setOnItemClickListener { _, _, position, _ -> playFrom(shown, position) }
        allowBtn.setOnClickListener { askPermission() }
    }

    private fun showTab(eq: Boolean) {
        tabLib.isSelected = !eq
        tabEq.isSelected = eq
        libPanel.visibility = if (eq) View.GONE else View.VISIBLE
        eqPanel.visibility = if (eq) View.VISIBLE else View.GONE
    }

    // ---------------- playback ----------------

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 -> if (shown.isNotEmpty()) playFrom(shown, 0)
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }
    }

    private fun playFrom(tracks: List<Track>, index: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }
        if (ids == queueIds && c.mediaItemCount == ids.size) {
            c.seekTo(index, 0)
        } else {
            c.setMediaItems(tracks.map { it.toMediaItem() }, index, 0)
            queueIds = ids
        }
        c.prepare()
        c.play()
    }

    private fun refresh() {
        val c = controller
        val playing = c?.isPlaying == true
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        spectrum.active = playing

        val item = c?.currentMediaItem
        if (c != null && item != null) {
            val md = item.mediaMetadata
            val artist = md.artist?.toString().orEmpty()
            val title = md.title?.toString().orEmpty()
            titleText.text = if (artist.isNotEmpty()) "$artist  —  $title" else title
            trackNo.text = String.format("%02d/%02d", c.currentMediaItemIndex + 1, c.mediaItemCount)
        } else {
            titleText.text = if (all.isEmpty()) "Pick a song to begin" else "Tap a song to play"
            trackNo.text = "--/--"
        }

        val pos = c?.currentPosition ?: 0L
        setFlag(flagPlay, playing)
        flagPlay.text = when {
            playing -> "PLAY"
            pos > 0 -> "PAUSE"
            else -> "STOP"
        }
        val shuffle = c?.shuffleModeEnabled == true
        val repeat = c?.repeatMode ?: Player.REPEAT_MODE_OFF
        setFlag(flagShuf, shuffle)
        setFlag(flagRep, repeat != Player.REPEAT_MODE_OFF)
        flagRep.text = if (repeat == Player.REPEAT_MODE_ONE) "REP1" else "REP"
        setFlag(flagEq, AudioFx.enabled)
        shuffleChip.isSelected = shuffle
        repeatChip.isSelected = repeat != Player.REPEAT_MODE_OFF
        repeatChip.text = when (repeat) {
            Player.REPEAT_MODE_ALL -> "Repeat: all"
            Player.REPEAT_MODE_ONE -> "Repeat: one"
            else -> "Repeat: off"
        }
        adapter.notifyDataSetChanged()
        updateTime()
    }

    private fun setFlag(v: TextView, on: Boolean) {
        v.setTextColor(getColor(if (on) R.color.vfd else R.color.vfd_dim))
        if (on) v.setShadowLayer(8f, 0f, 0f, getColor(R.color.vfd_glow)) else v.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun updateTime() {
        val c = controller
        val pos = c?.currentPosition ?: 0L
        val dur = c?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        timeText.text = if (showRemain && dur > 0) "-" + fmt(dur - pos) else fmt(pos)
        durText.text = if (dur > 0) fmt(dur) else "-:--"
        if (!dragging) seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun volumeCurve(p: Int): Float = (p / 100f).pow(1.6f)

    // ---------------- library ----------------

    private fun hasPermission() = checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        val askedBefore = prefs.getBoolean("asked_perm", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(audioPermission)) {
            // Android won't show the prompt again; send the person to the app's settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            prefs.edit().putBoolean("asked_perm", true).apply()
            requestPermissions(arrayOf(audioPermission), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission()) loadLibrary() else showNeedsPermission()
    }

    override fun onResume() {
        super.onResume()
        if (all.isEmpty() && hasPermission()) loadLibrary()
    }

    private fun showNeedsPermission() {
        emptyBox.visibility = View.VISIBLE
        allowBtn.visibility = View.VISIBLE
        emptyText.text = "Deckline plays the music stored on your phone.\nAllow access so it can find your songs."
        list.visibility = View.GONE
        countText.text = ""
        if (!prefs.getBoolean("asked_perm", false)) askPermission()
    }

    private var loading = false

    private fun loadLibrary() {
        if (loading) return
        loading = true
        countText.text = "Scanning…"
        io.execute {
            val found = mutableListOf<Track>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
            )
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cur ->
                    val iId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val iTitle = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val iArtist = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val iAlbum = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val iAlbumId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val iDur = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (cur.moveToNext()) {
                        val artist = cur.getString(iArtist)
                        found += Track(
                            id = cur.getLong(iId),
                            title = cur.getString(iTitle) ?: "Untitled",
                            artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Unknown artist" else artist,
                            album = cur.getString(iAlbum) ?: "",
                            albumId = cur.getLong(iAlbumId),
                            durationMs = cur.getLong(iDur)
                        )
                    }
                }
            } catch (_: Throwable) {
            }
            runOnUiThread {
                loading = false
                if (isFinishing) return@runOnUiThread
                all = found
                applyFilter()
                refresh()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) all else all.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        countText.text = when (all.size) {
            0 -> ""
            1 -> "1 song"
            else -> "${all.size} songs"
        }
        val empty = shown.isEmpty()
        emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        allowBtn.visibility = View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.text = if (all.isEmpty())
            "No music found on this phone.\nCopy MP3, M4A, FLAC or OGG files into your Music folder, then reopen Deckline."
        else "No songs match “$q”."
        adapter.notifyDataSetChanged()
    }

    // ---------------- equalizer ----------------

    private fun buildEqualizer() {
        eqContainer.removeAllViews()
        val eq = AudioFx.eq
        if (eq == null) {
            eqContainer.addView(label("The equalizer isn’t available on this phone.", 15f))
            return
        }
        val sw = Switch(this).apply {
            text = "Equalizer on"
            textSize = 16f
            setTextColor(getColor(R.color.ink))
            isChecked = AudioFx.enabled
            setPadding(0, 0, 0, dp(10))
            setOnCheckedChangeListener { _, on -> AudioFx.setEnabled(on); refresh() }
        }
        eqContainer.addView(sw)

        val bandCount = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange // millibels, e.g. [-1500, 1500]
        val min = range[0].toInt()
        val max = range[1].toInt()
        val bars = mutableListOf<SeekBar>()
        val values = mutableListOf<TextView>()

        fun syncBars() {
            for (b in 0 until bandCount) {
                val lvl = AudioFx.bandLevel(b)
                bars[b].progress = lvl - min
                values[b].text = String.format("%+.1f dB", lvl / 100f)
            }
        }

        // presets row
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Flat") { AudioFx.flat(); syncBars() })
        val presetCount = try { eq.numberOfPresets.toInt() } catch (_: Throwable) { 0 }
        for (i in 0 until presetCount) {
            val name = try { eq.getPresetName(i.toShort()) } catch (_: Throwable) { continue }
            if (name.equals("Normal", true) || name.equals("Flat", true)) continue
            row.addView(chip(name) { AudioFx.usePreset(i); syncBars() })
        }
        scroller.addView(row)
        eqContainer.addView(scroller)

        for (b in 0 until bandCount) {
            val hz = eq.getCenterFreq(b.toShort()) / 1000
            val name = if (hz >= 1000) String.format("%.1f kHz", hz / 1000f).replace(".0 ", " ") else "$hz Hz"
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            head.addView(label(name, 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColor(R.color.ink))
            })
            val value = label("", 14f).apply { typeface = android.graphics.Typeface.MONOSPACE }
            head.addView(value)
            values += value
            eqContainer.addView(head)

            val bar = SeekBar(this).apply {
                this.max = max - min
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.play))
                thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.play))
                setOnSeekBarChangeListener(object : SimpleSeek() {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val lvl = p + min
                        AudioFx.setBand(b, lvl)
                        value.text = String.format("%+.1f dB", lvl / 100f)
                    }
                })
            }
            bars += bar
            eqContainer.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        syncBars()
        eqContainer.addView(label("Turn the equalizer on, then pick a preset or drag the sliders. Your settings are remembered.", 13f).apply {
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColorStateList(R.color.chip_text))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(R.color.ink_soft))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- list ----------------

    inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val t = shown[position]
            v.findViewById<TextView>(R.id.title).text = t.title
            v.findViewById<TextView>(R.id.artist).text =
                if (t.album.isNotBlank()) "${t.artist}  ·  ${t.album}" else t.artist
            v.findViewById<TextView>(R.id.duration).text = if (t.durationMs > 0) fmt(t.durationMs) else ""
            val current = controller?.currentMediaItem?.mediaId == t.id.toString()
            v.setBackgroundColor(if (current) getColor(R.color.row_active) else Color.TRANSPARENT)
            return v
        }
    }

    private open class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    companion object {
        fun fmt(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000).toInt()
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }
}
