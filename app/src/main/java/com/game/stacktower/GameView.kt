package com.game.stacktower

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputFilter
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// ===================== DATA PEMAIN (disimpan lokal) =====================

class ScoreEntry(val name: String, val score: Int, val time: Long)

class GameData(context: Context) {
    private val p = context.getSharedPreferences("stack_tower", Context.MODE_PRIVATE)

    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) { p.edit().putString("name", v).apply() }
    var best: Int
        get() = p.getInt("best", 0)
        set(v) { p.edit().putInt("best", v).apply() }
    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
    var perfects: Int
        get() = p.getInt("perfects", 0)
        set(v) { p.edit().putInt("perfects", v).apply() }
    var bestCombo: Int
        get() = p.getInt("bestCombo", 0)
        set(v) { p.edit().putInt("bestCombo", v).apply() }
    var soundOn: Boolean
        get() = p.getBoolean("sound", true)
        set(v) { p.edit().putBoolean("sound", v).apply() }
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) { p.edit().putInt("theme", v).apply() }

    fun topScores(): List<ScoreEntry> {
        val raw = p.getString("scores", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";").mapNotNull {
            val parts = it.split("|")
            if (parts.size != 3) null else {
                val s = parts[1].toIntOrNull()
                val t = parts[2].toLongOrNull()
                if (s == null || t == null) null else ScoreEntry(parts[0], s, t)
            }
        }
    }

    /** Menyimpan skor, mengembalikan peringkat 1..5 atau 0 jika tidak masuk 5 besar. */
    fun addScore(name: String, score: Int, time: Long): Int {
        val list = (topScores() + ScoreEntry(name, score, time))
            .sortedWith(compareByDescending<ScoreEntry> { it.score }.thenBy { it.time })
            .take(5)
        p.edit().putString("scores", list.joinToString(";") { "${it.name}|${it.score}|${it.time}" }).apply()
        val idx = list.indexOfFirst { it.time == time }
        return if (idx >= 0) idx + 1 else 0
    }
}

// ===================== TAMPILAN GAME =====================

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class State { MENU, PLAYING, PAUSED, OVER, RANK, PROFILE, THEME }

    private class Block(var x: Float, var width: Float, val color: Int)
    private class Piece(
        val x: Float, val width: Float, val color: Int, val level: Int,
        var fall: Float = 0f, var vy: Float = 0f
    )
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int)
    private class Floater(var x: Float, var y: Float, val size: Float, val speed: Float, val color: Int)
    private class Btn(var label: String, val r: RectF, val color: Int, val onClick: () -> Unit)
    private class Theme(val name: String, val req: Int)

    private val store = GameData(context)
    private val sfx = SoundFx()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val rect = RectF()

    private val gold = Color.parseColor("#FFD54F")
    private val titles = listOf(
        0 to "Pemula", 5 to "Penyusun", 15 to "Tukang Bangunan",
        30 to "Arsitek", 50 to "Master Menara", 80 to "Legenda Pencakar Langit"
    )
    private val themes = listOf(
        Theme("Klasik", 0), Theme("Pastel", 5), Theme("Neon", 15),
        Theme("Emas", 30), Theme("Samudra", 50), Theme("Senja", 80)
    )
    private val bgTops = intArrayOf(
        Color.parseColor("#1E2A3A"), Color.parseColor("#3B2A5C"), Color.parseColor("#0B1026"),
        Color.parseColor("#1B4F5C"), Color.parseColor("#4A1E3A")
    )
    private val bgBottoms = intArrayOf(
        Color.parseColor("#2F4560"), Color.parseColor("#C86B85"), Color.parseColor("#1B3A5C"),
        Color.parseColor("#6FB7A8"), Color.parseColor("#F29E4C")
    )

    // Status game
    private val stack = mutableListOf<Block>()
    private val pieces = mutableListOf<Piece>()
    private val particles = mutableListOf<Particle>()
    private val floaters = mutableListOf<Floater>()
    private var current: Block? = null
    private var state = State.MENU
    private var direction = 1
    private var speed = 0f
    private var score = 0
    private var combo = 0
    private var runPerfects = 0
    private var bestRunCombo = 0
    private var isNewBest = false
    private var lastRank = 0
    private var lastEntryTime = 0L
    private var unlockedTheme = ""

    // Animasi
    private var popupText = ""
    private var popupAlpha = 0f
    private var intro = 1f
    private var shake = 0f
    private var time = 0f
    private var bgPos = 0f
    private var fade = 0f
    private var fadeDir = 0
    private var pending: (() -> Unit)? = null

    private var blockHeight = 0f
    private var baseline = 0f
    private var cameraY = 0f
    private var lastTime = 0L
    private val hueStart = (0..359).random().toFloat()
    private var askedName = false

    // Tombol
    private lateinit var soundBtn: Btn
    private lateinit var backBtn: Btn
    private lateinit var renameBtn: Btn
    private var menuBtns: List<Btn> = emptyList()
    private var overBtns: List<Btn> = emptyList()
    private var pauseBtns: List<Btn> = emptyList()
    private var rankBtns: List<Btn> = emptyList()
    private var profileBtns: List<Btn> = emptyList()
    private var themeBtns: List<Btn> = emptyList()
    private val pauseRect = RectF()
    private var pressedBtn: Btn? = null
    private var pressedRow = -1

    // Tema yang sedang dipakai (otomatis kembali ke Klasik jika belum terbuka)
    private val activeTheme: Int
        get() {
            val t = store.theme
            return if (t in themes.indices && store.best >= themes[t].req) t else 0
        }

    // ---------- Setup ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blockHeight = h / 16f
        baseline = h * 0.85f
        layout()
        floaters.clear()
        repeat(14) { floaters.add(newFloater(true)) }
        resetGame()
        state = State.MENU
        if (store.name.isEmpty() && !askedName) {
            askedName = true
            post { askName(true) }
        }
    }

    private fun newFloater(randomY: Boolean): Floater {
        val size = width * (0.06f + Random.nextFloat() * 0.10f)
        return Floater(
            Random.nextFloat() * width,
            if (randomY) Random.nextFloat() * height else height + size,
            size,
            height * (0.03f + Random.nextFloat() * 0.06f),
            Color.HSVToColor(floatArrayOf((hueStart + Random.nextFloat() * 80f) % 360f, 0.5f, 0.9f))
        )
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w * 0.7f
        val bh = h * 0.07f

        fun btn(label: String, cy: Float, color: Int, bwid: Float = bw, bhei: Float = bh, onClick: () -> Unit) =
            Btn(label, RectF(w / 2f - bwid / 2f, cy * h - bhei / 2f, w / 2f + bwid / 2f, cy * h + bhei / 2f), color, onClick)

        val green = Color.parseColor("#43A047")
        val blue = Color.parseColor("#3F8CFF")
        val purple = Color.parseColor("#8E6CF0")
        val orange = Color.parseColor("#F57C00")
        val grey = Color.parseColor("#546E7A")

        soundBtn = btn(soundLabel(), 0.855f, grey, bw * 0.6f, bh * 0.8f) { toggleSound() }
        menuBtns = listOf(
            btn("MAIN", 0.48f, green) { transitionTo { startGame() } },
            btn("PERINGKAT", 0.575f, blue) { transitionTo { state = State.RANK } },
            btn("PROFIL", 0.67f, purple) { transitionTo { state = State.PROFILE } },
            btn("TEMA", 0.765f, orange) { transitionTo { state = State.THEME } },
            soundBtn
        )
        overBtns = listOf(
            btn("MAIN LAGI", 0.63f, green) { transitionTo { startGame() } },
            btn("BAGIKAN", 0.72f, blue) { shareScore() },
            btn("MENU", 0.81f, grey) { transitionTo { goMenu() } }
        )
        pauseBtns = listOf(
            btn("LANJUT", 0.46f, green) { resumeGame() },
            btn("MENU", 0.56f, grey) { transitionTo { goMenu() } }
        )
        backBtn = btn("KEMBALI", 0.89f, grey) { transitionTo { goMenu() } }
        renameBtn = btn("GANTI NAMA", 0.395f, blue, bw * 0.55f, bh * 0.8f) { askName(false) }
        rankBtns = listOf(backBtn)
        profileBtns = listOf(renameBtn, backBtn)
        themeBtns = listOf(backBtn)

        val cx = w * 0.9f
        val cy = h * 0.07f
        val rad = w * 0.07f
        pauseRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
    }

    private fun soundLabel() = if (store.soundOn) "SUARA: ON" else "SUARA: OFF"

    private fun toggleSound() {
        store.soundOn = !store.soundOn
        soundBtn.label = soundLabel()
    }

    private fun activeButtons(): List<Btn> = when (state) {
        State.MENU -> menuBtns
        State.OVER -> overBtns
        State.PAUSED -> pauseBtns
        State.RANK -> rankBtns
        State.PROFILE -> profileBtns
        State.THEME -> themeBtns
        State.PLAYING -> emptyList()
    }

    private fun themeRow(i: Int): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * (0.20f + i * 0.105f)
        return RectF(w * 0.07f, top, w * 0.93f, top + h * 0.09f)
    }

    private fun rowAt(x: Float, y: Float): Int =
        themes.indices.firstOrNull { themeRow(it).contains(x, y) } ?: -1

    // ---------- Bagikan skor ----------

    private fun shareScore() {
        val text = if (score > 0) {
            "Aku dapat skor $score di Stack Tower! Bisa kalahkan aku?"
        } else {
            "Ayo coba Stack Tower, game menyusun menara yang bikin ketagihan!"
        }
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, "Bagikan skor")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Jika tidak ada aplikasi untuk berbagi, abaikan saja
        }
    }

    // ---------- Nama pemain ----------

    private fun cleanName(s: String): String =
        s.filter { it.isLetterOrDigit() || it == ' ' || it == '_' }.trim().take(12)

    private fun askName(first: Boolean) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            setSingleLine()
            hint = "Nama kamu (maks 12 karakter)"
            filters = arrayOf(InputFilter.LengthFilter(12))
            setText(store.name)
            setSelection(text.length)
        }
        val box = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(if (first) "Selamat datang! Siapa namamu?" else "Ganti nama")
            .setView(box)
            .setCancelable(!first)
            .setPositiveButton("Simpan") { _, _ ->
                val n = cleanName(input.text.toString())
                store.name = if (n.isEmpty()) store.name.ifEmpty { "Pemain" } else n
            }
            .create()
            .show()
    }

    // ---------- Alur layar ----------

    private fun transitionTo(action: () -> Unit) {
        if (fadeDir != 0) return
        pending = action
        fadeDir = 1
    }

    private fun goMenu() {
        state = State.MENU
        bgPos = 0f
    }

    private fun startGame() {
        bgPos = 0f
        resetGame()
    }

    private fun pauseGame() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    private fun resumeGame() {
        if (state == State.PAUSED) {
            state = State.PLAYING
            lastTime = System.nanoTime()
        }
    }

    /** Dipanggil dari tombol Back. Mengembalikan true jika sudah ditangani. */
    fun onBack(): Boolean {
        if (fadeDir != 0) return true
        return when (state) {
            State.PLAYING -> { pauseGame(); true }
            State.PAUSED -> { resumeGame(); true }
            State.OVER, State.RANK, State.PROFILE, State.THEME -> { transitionTo { goMenu() }; true }
            State.MENU -> false
        }
    }

    /** Dipanggil saat aplikasi ditinggalkan. */
    fun pausedByApp() = pauseGame()

    // ---------- Logika game ----------

    private fun colorForTheme(t: Int, level: Int): Int {
        val l = level.toFloat()
        val hsv = when (t) {
            1 -> floatArrayOf((hueStart + l * 8f) % 360f, 0.28f, 1.0f)
            2 -> floatArrayOf((hueStart + l * 8f) % 360f, 1.0f, 1.0f)
            3 -> floatArrayOf(40f + 8f * sin(l * 0.5f), 0.55f + 0.25f * sin(l * 0.7f), 0.97f)
            4 -> floatArrayOf(200f + 30f * sin(l * 0.35f), 0.6f, 0.9f)
            5 -> floatArrayOf((345f + 45f * (0.5f + 0.5f * sin(l * 0.3f))) % 360f, 0.65f, 1.0f)
            else -> floatArrayOf((hueStart + l * 8f) % 360f, 0.55f, 0.95f)
        }
        return Color.HSVToColor(hsv)
    }

    private fun colorFor(level: Int): Int = colorForTheme(activeTheme, level)

    private fun resetGame() {
        stack.clear()
        pieces.clear()
        particles.clear()
        score = 0
        combo = 0
        runPerfects = 0
        bestRunCombo = 0
        isNewBest = false
        unlockedTheme = ""
        popupAlpha = 0f
        cameraY = 0f
        intro = 0f
        state = State.PLAYING
        val baseWidth = width * 0.6f
        stack.add(Block((width - baseWidth) / 2f, baseWidth, colorFor(0)))
        spawnNext()
        lastTime = System.nanoTime()
    }

    private fun spawnNext() {
        val top = stack.last()
        val level = stack.size
        val fromLeft = level % 2 == 0
        current = Block(if (fromLeft) 0f else width - top.width, top.width, colorFor(level))
        direction = if (fromLeft) 1 else -1
        speed = width * (0.55f + min(score, 40) * 0.02f)
    }

    private fun popup(text: String) {
        popupText = text
        popupAlpha = 1f
    }

    private fun buzz(type: Int) {
        if (store.soundOn) performHapticFeedback(type)
    }

    private fun burst(level: Int, x: Float, w: Float) {
        val y = baseline - level * blockHeight + cameraY
        repeat(16) {
            particles.add(
                Particle(
                    x + Random.nextFloat() * w, y,
                    (Random.nextFloat() - 0.5f) * width * 0.8f,
                    -height * (0.05f + Random.nextFloat() * 0.35f),
                    1f,
                    if (Random.nextBoolean()) Color.WHITE else gold
                )
            )
        }
    }

    private fun dropBlock() {
        val cur = current ?: return
        val top = stack.last()
        val level = stack.size
        val left = max(cur.x, top.x)
        val right = min(cur.x + cur.width, top.x + top.width)
        val overlap = right - left

        if (overlap <= 0f) {
            pieces.add(Piece(cur.x, cur.width, cur.color, level))
            current = null
            endGame()
            return
        }

        if (abs(cur.x - top.x) < width * 0.015f) {
            // PERFECT
            cur.x = top.x
            combo++
            runPerfects++
            bestRunCombo = max(bestRunCombo, combo)
            if (combo >= 3) {
                // Bonus combo: balok melebar lagi
                val nw = min(cur.width + width * 0.03f, width * 0.6f)
                cur.x = (cur.x - (nw - cur.width) / 2f).coerceIn(0f, width - nw)
                cur.width = nw
            }
            popup(if (combo > 1) "PERFECT! x$combo" else "PERFECT!")
            burst(level, cur.x, cur.width)
            buzz(HapticFeedbackConstants.LONG_PRESS)
            if (store.soundOn) sfx.playPerfect(combo - 1)
        } else {
            combo = 0
            val curRight = cur.x + cur.width
            val topRight = top.x + top.width
            if (cur.x < top.x) {
                pieces.add(Piece(cur.x, top.x - cur.x, cur.color, level))
            }
            if (curRight > topRight) {
                pieces.add(Piece(topRight, curRight - topRight, cur.color, level))
            }
            cur.x = left
            cur.width = overlap
            buzz(HapticFeedbackConstants.KEYBOARD_TAP)
            if (store.soundOn) sfx.playDrop()
        }
        stack.add(cur)
        score++
        if (score % 10 == 0) popup("LEVEL ${score / 10}!")
        spawnNext()
    }

    private fun endGame() {
        state = State.OVER
        val oldBest = store.best
        isNewBest = score > oldBest
        if (isNewBest) store.best = score

        // Cek apakah ada tema yang baru terbuka
        unlockedTheme = ""
        if (isNewBest) {
            val t = themes.lastOrNull { it.req > 0 && it.req > oldBest && it.req <= score }
            if (t != null) unlockedTheme = t.name
        }

        store.games = store.games + 1
        store.perfects = store.perfects + runPerfects
        store.bestCombo = max(store.bestCombo, bestRunCombo)
        lastRank = 0
        lastEntryTime = 0L
        if (score > 0) {
            lastEntryTime = System.currentTimeMillis()
            lastRank = store.addScore(store.name.ifEmpty { "Pemain" }, score, lastEntryTime)
        }
        shake = 1f
        buzz(HapticFeedbackConstants.LONG_PRESS)
        if (store.soundOn) sfx.playGameOver()
    }

    private fun update(dt: Float) {
        time += dt

        // Transisi fade
        if (fadeDir == 1) {
            fade += dt * 5f
            if (fade >= 1f) {
                fade = 1f
                pending?.invoke()
                pending = null
                fadeDir = -1
            }
        } else if (fadeDir == -1) {
            fade -= dt * 4f
            if (fade <= 0f) {
                fade = 0f
                fadeDir = 0
            }
        }
        shake = max(0f, shake - dt * 2.5f)

        val inGame = state == State.PLAYING || state == State.PAUSED || state == State.OVER
        val target = if (inGame) score / 10f else 0f
        bgPos += (target - bgPos) * min(1f, 1.5f * dt)

        if (!inGame) {
            floaters.forEach {
                it.y -= it.speed * dt
                if (it.y < -it.size) {
                    it.y = height + it.size
                    it.x = Random.nextFloat() * width
                }
            }
            return
        }
        if (state == State.PAUSED) return

        if (state == State.PLAYING) {
            intro = min(1f, intro + dt * 2.2f)
            current?.let { cur ->
                cur.x += direction * speed * dt
                if (cur.x + cur.width > width) { cur.x = width - cur.width; direction = -1 }
                if (cur.x < 0f) { cur.x = 0f; direction = 1 }
            }
            val camTarget = max(0f, (stack.size - 6) * blockHeight)
            cameraY += (camTarget - cameraY) * min(1f, 6f * dt)
        }

        val pit = pieces.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.vy += height * 2.2f * dt
            p.fall += p.vy * dt
            if (p.fall > height * 1.5f) pit.remove()
        }

        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.vy += height * 1.2f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt * 1.6f
            if (p.life <= 0f) iter.remove()
        }

        popupAlpha = max(0f, popupAlpha - dt * 1.4f)
    }

    // ---------- Gambar: utilitas ----------

    private fun drawText(
        canvas: Canvas, s: String, x: Float, y: Float, size: Float,
        align: Paint.Align = Paint.Align.CENTER, color: Int = Color.WHITE, alpha: Int = 255
    ) {
        textPaint.textSize = size
        textPaint.textAlign = align
        textPaint.color = color
        textPaint.alpha = alpha
        canvas.drawText(s, x, y, textPaint)
    }

    private fun drawButton(canvas: Canvas, b: Btn) {
        val pressed = b === pressedBtn
        rect.set(b.r)
        if (pressed) rect.inset(b.r.width() * 0.015f, b.r.height() * 0.04f)
        val rad = b.r.height() * 0.3f
        val depth = height * 0.007f
        paint.color = ColorUtils.blendARGB(b.color, Color.BLACK, 0.35f)
        canvas.drawRoundRect(rect.left, rect.top + depth, rect.right, rect.bottom + depth, rad, rad, paint)
        paint.color = b.color
        canvas.drawRoundRect(rect, rad, rad, paint)
        val size = b.r.height() * 0.42f
        drawText(canvas, b.label, rect.centerX(), rect.centerY() + size * 0.35f, size)
    }

    private fun drawBackground(canvas: Canvas) {
        val n = bgTops.size
        val base = floor(bgPos)
        val i = base.toInt() % n
        val j = (i + 1) % n
        val t = bgPos - base
        val top = ColorUtils.blendARGB(bgTops[i], bgTops[j], t)
        val bot = ColorUtils.blendARGB(bgBottoms[i], bgBottoms[j], t)
        bgPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bot, Shader.TileMode.CLAMP)
        canvas.drawRect(-80f, -80f, width + 80f, height + 80f, bgPaint)
    }

    private fun drawFloaters(canvas: Canvas) {
        floaters.forEach {
            paint.color = it.color
            paint.alpha = 70
            canvas.drawRoundRect(it.x, it.y, it.x + it.size, it.y + it.size, 14f, 14f, paint)
        }
    }

    private fun drawBlock(canvas: Canvas, x: Float, w: Float, color: Int, level: Int, dy: Float = 0f) {
        val y = baseline - level * blockHeight + cameraY + dy
        if (y > height || y < -blockHeight) return
        paint.color = color
        rect.set(x, y, x + w, y + blockHeight - 3f)
        canvas.drawRoundRect(rect, 8f, 8f, paint)
    }

    private fun titleIndex(best: Int): Int {
        var idx = 0
        titles.forEachIndexed { i, t -> if (best >= t.first) idx = i }
        return idx
    }

    // ---------- Gambar: layar ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = min(0.05f, (now - lastTime) / 1_000_000_000f)
        lastTime = now
        update(dt)

        canvas.save()
        if (shake > 0f) {
            canvas.translate(
                (Random.nextFloat() - 0.5f) * shake * width * 0.04f,
                (Random.nextFloat() - 0.5f) * shake * width * 0.04f
            )
        }
        drawBackground(canvas)
        when (state) {
            State.MENU -> drawMenu(canvas)
            State.RANK -> drawRank(canvas)
            State.PROFILE -> drawProfile(canvas)
            State.THEME -> drawTheme(canvas)
            State.PLAYING, State.PAUSED, State.OVER -> drawPlay(canvas)
        }
        canvas.restore()

        if (fade > 0f) {
            paint.color = Color.argb((fade * 255).toInt().coerceIn(0, 255), 10, 14, 26)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        postInvalidateOnAnimation()
    }

    private fun drawMenu(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawFloaters(canvas)

        val bounce = sin(time * 2.2f) * h * 0.006f
        drawText(canvas, "STACK", w / 2f, h * 0.13f + bounce, w * 0.20f)
        val accent = Color.HSVToColor(floatArrayOf((time * 30f) % 360f, 0.5f, 1f))
        drawText(canvas, "TOWER", w / 2f, h * 0.21f - bounce, w * 0.17f, color = accent)

        // Menara mini dekoratif (mengikuti tema yang dipakai)
        for (i in 0..4) {
            val bwid = w * (0.38f - i * 0.05f)
            val sway = sin(time * 2f + i) * w * 0.02f
            val y = h * 0.32f - i * h * 0.024f
            paint.color = colorFor(i)
            canvas.drawRoundRect(w / 2f - bwid / 2f + sway, y, w / 2f + bwid / 2f + sway, y + h * 0.02f, 10f, 10f, paint)
        }

        drawText(canvas, "Halo, ${store.name.ifEmpty { "Pemain" }}!", w / 2f, h * 0.385f, w * 0.055f)
        drawText(canvas, "Skor terbaik: ${store.best}", w / 2f, h * 0.42f, w * 0.04f, alpha = 200)

        menuBtns.forEach { drawButton(canvas, it) }
    }

    private fun drawRank(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawFloaters(canvas)
        drawText(canvas, "5 BESAR", w / 2f, h * 0.12f, w * 0.11f)
        drawText(canvas, "Skor tertinggi di perangkat ini", w / 2f, h * 0.16f, w * 0.038f, alpha = 200)

        val list = store.topScores()
        val medal = intArrayOf(gold, Color.parseColor("#CFD8DC"), Color.parseColor("#FFAB91"), Color.WHITE, Color.WHITE)
        for (i in 0 until 5) {
            val top = h * (0.21f + i * 0.12f)
            val bottom = top + h * 0.10f
            val entry = list.getOrNull(i)
            val mine = entry != null && entry.time == lastEntryTime
            paint.color = if (mine) Color.argb(140, 255, 213, 79) else Color.argb(110, 0, 0, 0)
            canvas.drawRoundRect(w * 0.07f, top, w * 0.93f, bottom, 28f, 28f, paint)

            val cy = (top + bottom) / 2f
            paint.color = medal[i]
            paint.alpha = if (entry != null) 255 else 90
            canvas.drawCircle(w * 0.17f, cy, h * 0.032f, paint)
            drawText(canvas, "${i + 1}", w * 0.17f, cy + w * 0.02f, w * 0.055f, color = Color.parseColor("#1E2A3A"))

            if (entry != null) {
                drawText(canvas, entry.name, w * 0.27f, cy + w * 0.02f, w * 0.058f, Paint.Align.LEFT)
                drawText(canvas, "${entry.score}", w * 0.88f, cy + w * 0.027f, w * 0.075f, Paint.Align.RIGHT)
            } else {
                drawText(canvas, "-", w * 0.27f, cy + w * 0.02f, w * 0.058f, Paint.Align.LEFT, alpha = 110)
            }
        }
        rankBtns.forEach { drawButton(canvas, it) }
    }

    private fun drawProfile(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawFloaters(canvas)
        val name = store.name.ifEmpty { "Pemain" }

        // Avatar
        val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        paint.color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.9f))
        canvas.drawCircle(w / 2f, h * 0.17f, w * 0.12f, paint)
        drawText(canvas, name.first().uppercase(), w / 2f, h * 0.17f + w * 0.06f, w * 0.17f)

        val idx = titleIndex(store.best)
        drawText(canvas, name, w / 2f, h * 0.315f, w * 0.075f)
        drawText(canvas, titles[idx].second, w / 2f, h * 0.35f, w * 0.045f, color = gold)

        // Kartu statistik
        val l1 = w * 0.08f; val r1 = w * 0.48f
        val l2 = w * 0.52f; val r2 = w * 0.92f
        statCard(canvas, l1, h * 0.45f, r1, h * 0.55f, "Skor terbaik", "${store.best}")
        statCard(canvas, l2, h * 0.45f, r2, h * 0.55f, "Total main", "${store.games}")
        statCard(canvas, l1, h * 0.57f, r1, h * 0.67f, "Total perfect", "${store.perfects}")
        statCard(canvas, l2, h * 0.57f, r2, h * 0.67f, "Combo terbaik", "${store.bestCombo}")

        // Progres gelar
        val next = titles.getOrNull(idx + 1)
        val cur = titles[idx].first
        val progress: Float
        if (next != null) {
            progress = ((store.best - cur).toFloat() / (next.first - cur)).coerceIn(0f, 1f)
            drawText(canvas, "Menuju ${next.second}: ${store.best}/${next.first}", w / 2f, h * 0.725f, w * 0.042f)
        } else {
            progress = 1f
            drawText(canvas, "Gelar tertinggi tercapai!", w / 2f, h * 0.725f, w * 0.042f, color = gold)
        }
        val barTop = h * 0.74f
        val barH = h * 0.016f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(w * 0.1f, barTop, w * 0.9f, barTop + barH, barH, barH, paint)
        if (progress > 0f) {
            paint.color = gold
            canvas.drawRoundRect(w * 0.1f, barTop, w * 0.1f + w * 0.8f * progress, barTop + barH, barH, barH, paint)
        }

        profileBtns.forEach { drawButton(canvas, it) }
    }

    private fun statCard(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, value: String) {
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(l, t, r, b, 28f, 28f, paint)
        drawText(canvas, value, (l + r) / 2f, t + (b - t) * 0.58f, width * 0.075f)
        drawText(canvas, label, (l + r) / 2f, t + (b - t) * 0.86f, width * 0.034f, alpha = 190)
    }

    private fun drawTheme(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawFloaters(canvas)
        drawText(canvas, "TEMA", w / 2f, h * 0.12f, w * 0.11f)
        drawText(canvas, "Naikkan skor untuk membuka tema baru", w / 2f, h * 0.16f, w * 0.038f, alpha = 200)

        val active = activeTheme
        for (i in themes.indices) {
            val r = themeRow(i)
            val unlocked = store.best >= themes[i].req
            val selected = i == active

            paint.color = if (selected) Color.argb(140, 255, 213, 79) else Color.argb(110, 0, 0, 0)
            canvas.drawRoundRect(r, 28f, 28f, paint)

            // Pratinjau warna: 5 balok kecil
            val sw = w * 0.05f
            val gap = w * 0.008f
            val x0 = w * 0.11f
            val sh = r.height() * 0.5f
            val top = r.centerY() - sh / 2f
            for (k in 0..4) {
                paint.color = colorForTheme(i, k)
                if (!unlocked) paint.alpha = 70
                rect.set(x0 + k * (sw + gap), top, x0 + k * (sw + gap) + sw, top + sh)
                canvas.drawRoundRect(rect, 6f, 6f, paint)
            }

            val textX = w * 0.47f
            drawText(canvas, themes[i].name, textX, r.centerY() - h * 0.004f, w * 0.055f,
                Paint.Align.LEFT, alpha = if (unlocked) 255 else 140)
            when {
                selected -> drawText(canvas, "Sedang dipakai", textX, r.centerY() + h * 0.026f, w * 0.036f,
                    Paint.Align.LEFT, color = gold)
                unlocked -> drawText(canvas, "Tap untuk pakai", textX, r.centerY() + h * 0.026f, w * 0.036f,
                    Paint.Align.LEFT, alpha = 200)
                else -> drawText(canvas, "Terkunci: butuh skor ${themes[i].req}", textX, r.centerY() + h * 0.026f,
                    w * 0.036f, Paint.Align.LEFT, alpha = 150)
            }
        }
        themeBtns.forEach { drawButton(canvas, it) }
    }

    private fun drawPlay(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Menara
        val off = (1f - intro).pow(3) * h * 0.35f
        stack.forEachIndexed { i, b -> drawBlock(canvas, b.x, b.width, b.color, i, off) }
        current?.let { drawBlock(canvas, it.x, it.width, it.color, stack.size, off) }
        pieces.forEach { drawBlock(canvas, it.x, it.width, it.color, it.level, it.fall) }

        // Partikel
        val s = w * 0.012f
        particles.forEach {
            paint.color = it.color
            paint.alpha = (255 * it.life).toInt().coerceIn(0, 255)
            canvas.drawRect(it.x, it.y, it.x + s, it.y + s, paint)
        }

        if (state != State.OVER) {
            // Skor
            drawText(canvas, score.toString(), w / 2f, h * 0.12f, w * 0.16f)
            drawText(canvas, "Terbaik: ${store.best}", w / 2f, h * 0.165f, w * 0.045f, alpha = 210)

            // Tombol pause
            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(pauseRect.centerX(), pauseRect.centerY(), pauseRect.width() * 0.36f, paint)
            paint.color = Color.WHITE
            val bw = w * 0.012f
            val bh = w * 0.04f
            canvas.drawRect(pauseRect.centerX() - bw * 1.6f, pauseRect.centerY() - bh / 2f,
                pauseRect.centerX() - bw * 0.6f, pauseRect.centerY() + bh / 2f, paint)
            canvas.drawRect(pauseRect.centerX() + bw * 0.6f, pauseRect.centerY() - bh / 2f,
                pauseRect.centerX() + bw * 1.6f, pauseRect.centerY() + bh / 2f, paint)

            // Popup (PERFECT, LEVEL)
            if (popupAlpha > 0f) {
                drawText(canvas, popupText, w / 2f, h * 0.26f, w * 0.09f, color = gold,
                    alpha = (255 * min(1f, popupAlpha)).toInt())
            }

            // Petunjuk untuk pemain baru
            if (state == State.PLAYING && score == 0 && store.games < 3) {
                val a = (150 + 100 * sin(time * 4f)).toInt().coerceIn(0, 255)
                drawText(canvas, "Tap untuk menjatuhkan balok", w / 2f, h * 0.36f, w * 0.05f, alpha = a)
            }
        }

        when (state) {
            State.PAUSED -> {
                paint.color = Color.argb(160, 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, paint)
                drawText(canvas, "JEDA", w / 2f, h * 0.36f, w * 0.13f)
                pauseBtns.forEach { drawButton(canvas, it) }
            }
            State.OVER -> drawOver(canvas)
            else -> {}
        }
    }

    private fun drawOver(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.argb(215, 16, 22, 36)
        canvas.drawRoundRect(w * 0.08f, h * 0.19f, w * 0.92f, h * 0.87f, 44f, 44f, paint)

        drawText(canvas, "GAME OVER", w / 2f, h * 0.26f, w * 0.115f)
        drawText(canvas, "SKOR", w / 2f, h * 0.315f, w * 0.045f, alpha = 190)
        drawText(canvas, "$score", w / 2f, h * 0.395f, w * 0.18f)

        if (isNewBest) {
            drawText(canvas, "REKOR BARU!", w / 2f, h * 0.45f, w * 0.062f, color = gold)
        } else {
            drawText(canvas, "Terbaik: ${store.best}", w / 2f, h * 0.45f, w * 0.052f, alpha = 220)
        }
        if (score > 0) {
            if (lastRank > 0) {
                drawText(canvas, "Peringkat #$lastRank di 5 Besar!", w / 2f, h * 0.495f, w * 0.045f, color = gold)
            } else {
                drawText(canvas, "Belum masuk 5 Besar, coba lagi!", w / 2f, h * 0.495f, w * 0.042f, alpha = 200)
            }
        }
        if (unlockedTheme.isNotEmpty()) {
            val a = (200 + 55 * sin(time * 5f)).toInt().coerceIn(0, 255)
            drawText(canvas, "Tema baru terbuka: $unlockedTheme!", w / 2f, h * 0.545f, w * 0.048f,
                color = gold, alpha = a)
        }
        overBtns.forEach { drawButton(canvas, it) }
    }

    // ---------- Sentuhan ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fadeDir != 0) return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (state == State.PLAYING) {
                    if (pauseRect.contains(event.x, event.y)) pauseGame() else dropBlock()
                } else {
                    pressedBtn = activeButtons().firstOrNull { it.r.contains(event.x, event.y) }
                    pressedRow = if (state == State.THEME && pressedBtn == null) rowAt(event.x, event.y) else -1
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val b = pressedBtn
                val row = pressedRow
                pressedBtn = null
                pressedRow = -1
                if (b != null && b.r.contains(event.x, event.y)) {
                    buzz(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (store.soundOn) sfx.playDrop()
                    b.onClick()
                    performClick()
                } else if (row >= 0 && state == State.THEME && rowAt(event.x, event.y) == row) {
                    if (store.best >= themes[row].req) {
                        store.theme = row
                        buzz(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (store.soundOn) sfx.playDrop()
                    } else {
                        buzz(HapticFeedbackConstants.LONG_PRESS)
                    }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedBtn = null
                pressedRow = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}