package com.natrasta.inzipsolver

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Capture d'écran réduite (environ 720 px de large), pixels au format 0xRRGGBB. */
class Screen(val w: Int, val h: Int, val px: IntArray) {
    fun r(i: Int) = (px[i] shr 16) and 255
    fun g(i: Int) = (px[i] shr 8) and 255
    fun b(i: Int) = px[i] and 255
    fun lum(i: Int) = (r(i) + g(i) + b(i)) / 3f
    fun sat(i: Int): Float { val mx = max(r(i), max(g(i), b(i))); val mn = min(r(i), min(g(i), b(i))); return if (mx > 0) (mx - mn).toFloat() / mx else 0f }
    fun value(i: Int) = max(r(i), max(g(i), b(i)))
    fun gray(): FloatArray = FloatArray(px.size) { (r(it) * 299 + g(it) * 587 + b(it) * 114) / 1000f }
}

/** Repère le plateau de jeu (carré) et son nombre de cases, quel que soit le jeu. */
object Board {
    class Geo(val left: Int, val top: Int, val right: Int, val bottom: Int, val n: Int,
              val x0: Double, val y0: Double, val cw: Double, val ch: Double, val bg: IntArray) {
        fun cx(c: Int) = x0 + (c + .5) * cw
        fun cy(r: Int) = y0 + (r + .5) * ch
    }

    fun find(s: Screen): Geo? {
        val W = s.w; val H = s.h
        val bg = IntArray(3) { ch -> val hist = IntArray(256)
            for (i in s.px.indices) hist[(s.px[i] shr (16 - 8 * ch)) and 255]++
            var acc = 0; var m = 0; for (k in 0 until 256) { acc += hist[k]; if (acc >= s.px.size / 2) { m = k; break } }; m }
        val nb = BooleanArray(W * H) { i -> val dr = s.r(i) - bg[0]; val dg = s.g(i) - bg[1]; val db = s.b(i) - bg[2]; dr * dr + dg * dg + db * db > 144 }
        val colRun = IntArray(W); val colEnd = IntArray(W); val cur = IntArray(W)
        for (y in 0 until H) for (x in 0 until W) {
            cur[x] = if (nb[y * W + x]) cur[x] + 1 else 0
            if (cur[x] > colRun[x]) { colRun[x] = cur[x]; colEnd[x] = y }
        }
        val rowRun = IntArray(H); val rowEnd = IntArray(H)
        for (y in 0 until H) { var c = 0; for (x in 0 until W) { c = if (nb[y * W + x]) c + 1 else 0; if (c > rowRun[y]) { rowRun[y] = c; rowEnd[y] = x } } }
        val cols = (0 until W).filter { colRun[it] >= 0.6 * W }
        if (cols.isEmpty()) return null
        val left = cols.first(); val right = cols.last(); val side = right - left
        if (side < 0.5 * W) return null
        val xm = colRun.indices.maxByOrNull { colRun[it] }!!
        val start = colEnd[xm] - colRun[xm] + 1
        val cyc = (start + colEnd[xm]) / 2.0
        var t = cyc - side / 2.0; var b = cyc + side / 2.0
        val rows = (0 until H).filter { rowRun[it] >= 0.6 * W && rowEnd[it] - rowRun[it] + 1 >= left - 0.02 * W && rowEnd[it] <= right + 0.02 * W }
        rows.filter { Math.abs(it - t) < 0.05 * side }.minByOrNull { Math.abs(it - t) }?.let { t = it.toDouble() }
        rows.filter { Math.abs(it - b) < 0.05 * side }.minByOrNull { Math.abs(it - b) }?.let { b = it.toDouble() }
        val top = t.roundToInt().coerceIn(0, H - 1); val bottom = b.roundToInt().coerceIn(0, H - 1)
        if (bottom - top < 10) return null

        val sw = right - left + 1; val sh = bottom - top + 1
        val L = FloatArray(W * H) { s.lum(it) }
        val gx = DoubleArray(sw - 2) { i -> var a = 0.0; for (y in top..bottom) a += Math.abs(L[y * W + left + i + 2] - L[y * W + left + i]); a / sh }
        val gy = DoubleArray(sh - 2) { i -> var a = 0.0; for (x in left..right) a += Math.abs(L[(top + i + 2) * W + x] - L[(top + i) * W + x]); a / sw }
        fun edges(p: DoubleArray): Pair<Int, Int> {
            val m = p.average(); val size = p.size; val lim = (0.04 * size).toInt() + 1
            val pk = (1 until size - 1).filter { p[it] > 3 * m && p[it] >= p[it - 1] && p[it] >= p[it + 1] }
            return Pair(pk.firstOrNull { it < lim } ?: 0, pk.lastOrNull { it > size - 1 - lim } ?: (size - 1))
        }
        val ex = edges(gx); val ey = edges(gy)
        val mean = (gx.sum() + gy.sum()) / (gx.size + gy.size)
        if (mean <= 0) return null
        val score = HashMap<Int, Double>()
        for (n in 3..12) {
            var mn = Double.MAX_VALUE
            for ((p, e) in listOf(gx to ex, gy to ey)) {
                val e0 = e.first; val e1 = e.second
                for (k in 1 until n) {
                    val c = Math.round(e0 + k * (e1 - e0).toDouble() / n).toInt()
                    val w = max(2, (0.05 * (e1 - e0) / n).toInt())
                    var mx = 0.0
                    for (i in max(0, c - w)..min(p.size - 1, c + w)) mx = max(mx, p[i])
                    mn = min(mn, mx)
                }
            }
            score[n] = mn / mean
        }
        val best = score.values.maxOrNull() ?: return null
        val n = (4..12).filter { score[it]!! >= max(2.0, 0.12 * best) }.maxOrNull() ?: return null
        val x0 = left + 1.0 + ex.first; val y0 = top + 1.0 + ey.first
        return Geo(left, top, right, bottom, n, x0, y0, (ex.second - ex.first).toDouble() / n, (ey.second - ey.first).toDouble() / n, bg)
    }
}

/** Lecture de chiffres par comparaison de formes. */
object Glyph {
    const val G = 20
    private val SUDOKU_RAW = mapOf(
        '1' to "0000000000003900000000000000137999000000000000399999990000000000009999999900000000000098645999000000000000320039990000000000000000399900000000000000003999000000000000000039990000000000000000399900000000000000003999000000000000000039990000000000000000399900000000000000003999000000000000000039990000000000000000399900000000000000003999000000000000000039990000000000000000399900000000000000001799000000",
        '2' to "0000000237953100000000000199999999100000000019998679999100000000999820028995000000039993000059990000000399600000399900000000000000004999000000000000000179950000000000000004999200000000000000179950000000000000016997100000000000001699610000000000000169971000000000000003999400000000000000289940000000000000029994000000000000002999400000000000000099999999999930000000999999999999300000009999999999993000",
        '3' to "0000001599995300000000000599999999500000000029998666999700000000999800004999200000009992000009996000000033300000099960000000000000000999200000000000000049970000000000006666999200000000000099999500000000000000999999500000000000000004899700000000000000000899600000000000000006996000000233300000069960000002999200000799600000009996000049996000000069998666999700000000059999999952000000000015999993000000",
        '4' to "0000000000179990000000000000006999900000000000000399999000000000000016999990000000000000399999900000000000029966999000000000000797169990000000000069950699900000000000998206999000000000059940069990000000002997000699900000000289920006999000000005993000069990000000179999999999999300002899999999999993000004666666689996620000000000000699900000000000000006999000000000000000069990000000000000000699900000",
        '5' to "0000179999999996000000003999999999960000000039999999999500000000399900000000000000003997000000000000000049930000000000000000699300000000000000006997689860000000000069999999995000000000699933399993000000002660000299960000000000000000399710000000000000002999300000000000000009993000000133300000299930000003999100005997100000039997000099960000000049997669999300000000159999999940000000000027999930000000",
        '6' to "0000000003599600000000000002999996000000000000299996640000000000049996000000000000002899400000000000000059970000000000000000699300000000000000039990699996200000000399999999998200000003999953359995000000039992000079971000000399900000299930000003999000000999300000039990000009993000000399900000099930000002899200002998200000006997000079960000000017999669999300000000017999999940000000000003999930000000"
    )
    val SUDOKU: Map<Char, FloatArray> = SUDOKU_RAW.mapValues { (_, s) -> FloatArray(s.length) { s[it].digitToInt() / 9f } }

    /** Lit un nombre écrit dans le masque (ww x hh). Renvoie 0 si rien n'est lisible. */
    fun number(mask: BooleanArray, ww: Int, hh: Int, tpl: Map<Char, FloatArray>): Int {
        val s = features(mask, ww, hh).joinToString("") { f ->
            var bk = '?'; var bs = Float.MAX_VALUE
            for ((k, t) in tpl) { var e = 0f; for (i in 0 until G * G) { val q = f[i] - t[i]; e += q * q }; if (e < bs) { bs = e; bk = k } }
            bk.toString()
        }
        return s.toIntOrNull() ?: 0
    }

    fun features(m: BooleanArray, ww: Int, hh: Int): List<FloatArray> {
        val lab = IntArray(ww * hh) { -1 }
        class Box(var x0: Int, var x1: Int, var y0: Int, var y1: Int, val ids: MutableList<Int>, val cnt: Int)
        val comps = ArrayList<Box>(); val st = ArrayDeque<Int>()
        for (i in 0 until ww * hh) if (m[i] && lab[i] < 0) {
            val id = comps.size; lab[i] = id; st.addLast(i)
            var cnt = 0; var mnx = Int.MAX_VALUE; var mxx = -1; var mny = Int.MAX_VALUE; var mxy = -1
            while (st.isNotEmpty()) {
                val p = st.removeLast(); val px = p % ww; val py = p / ww; cnt++
                mnx = min(mnx, px); mxx = max(mxx, px); mny = min(mny, py); mxy = max(mxy, py)
                if (px + 1 < ww && m[p + 1] && lab[p + 1] < 0) { lab[p + 1] = id; st.addLast(p + 1) }
                if (px > 0 && m[p - 1] && lab[p - 1] < 0) { lab[p - 1] = id; st.addLast(p - 1) }
                if (py + 1 < hh && m[p + ww] && lab[p + ww] < 0) { lab[p + ww] = id; st.addLast(p + ww) }
                if (py > 0 && m[p - ww] && lab[p - ww] < 0) { lab[p - ww] = id; st.addLast(p - ww) }
            }
            comps.add(Box(mnx, mxx, mny, mxy, mutableListOf(id), cnt))
        }
        if (comps.isEmpty()) return emptyList()
        val mx = comps.maxOf { it.cnt }
        val merged = ArrayList<Box>()
        for (b in comps.filter { it.cnt >= 0.15 * mx }.sortedBy { it.x0 }) {
            val l = merged.lastOrNull()
            if (l != null && b.x0 <= l.x1) { l.x1 = max(l.x1, b.x1); l.y0 = min(l.y0, b.y0); l.y1 = max(l.y1, b.y1); l.ids.addAll(b.ids) }
            else merged.add(Box(b.x0, b.x1, b.y0, b.y1, b.ids.toMutableList(), b.cnt))
        }
        return merged.map { b ->
            val set = b.ids.toHashSet(); val hgt = b.y1 - b.y0 + 1; val wid = b.x1 - b.x0 + 1
            val sc = G.toDouble() / hgt; val ox = (G - wid * sc) / 2
            val out = FloatArray(G * G)
            for (ty in 0 until G) for (tx in 0 until G) {
                var a = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val px = (tx + (sx + .5) / 3 - ox) / sc; val py = (ty + (sy + .5) / 3) / sc
                    if (px >= 0 && px < wid && py >= 0 && py < hgt && lab[(b.y0 + floor(py).toInt()) * ww + b.x0 + floor(px).toInt()] in set) a++
                }
                out[ty * G + tx] = a / 9f
            }
            out
        }
    }
}
