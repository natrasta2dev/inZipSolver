package com.natrasta.inzipsolver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Lit une grille de Zip (taille, chiffres, murs) depuis une image en niveaux de gris. */
object ZipReader {
    class Grid(
        val n: Int,
        val h: Array<BooleanArray>,   // mur sous la case (r, c)
        val v: Array<BooleanArray>,   // mur à droite de la case (r, c)
        val nums: IntArray,
        val unread: Int,
        val x0: Double, val y0: Double, val d: Double
    )

    private const val G = 20
    private val RAW = mapOf(
        '0' to "0000004899998400000000000599999999500000000039999537999500000000699710017996000000039994000039993000000399930000399930000005999300001799600000069993000006996000000699930000069960000006999300000699600000069993000006996000000699930000069960000006999300000699600000069993000028996000000399930000399930000003999500003999300000006998200289960000000039999768999400000000049999999940000000000027999972000000",
        '1' to "0000000000026830000000000000268999300000000000489999993000000000039999999930000000000399955999300000000003520039993000000000000000399930000000000000003999300000000000000039993000000000000000399930000000000000003999300000000000000039993000000000000000399930000000000000003999300000000000000039993000000000000000399930000000000000003999300000000000000039993000000000000000399930000000000000003999300000",
        '2' to "0000004899998400000000002899999999820000000069997359999500000003999400017998200000069971000049993000000699600000399930000000000000005999300000000000000069960000000000000005999400000000000000399960000000000000028997100000000000002899710000000000000289994000000000000028999400000000000000599940000000000000059994000000000000005999400000000000000599999999999960000006999999999999600000069999999999996000",
        '3' to "0000004899998400000000002899999999500000000289997359999500000005999400017998200000069971000049993000000000000000399930000000000000005999300000000000000289960000000000046679997100000000000699999510000000000006999999500000000000013335999500000000000000004999300000000000000039995000000466400000399960000006996000003999400000069995000289993000000399998668999600000000499999999971000000000159999972000000",
        '4' to "0000000000289960000000000000006999600000000000000399996000000000000028999960000000000000599999600000000000039977996000000000002899369960000000000059940699600000000002897106996000000000069940069960000000005996000699600000000289710006996000000006998666689986620000399999999999999300003999999999999993000013333333379973310000000000000699600000000000000006996000000000000000069960000000000000000699600000",
        '5' to "0000099999999999000000000999999999990000000049999999999900000000699900000000000000006999000000000000000069950000000000000000699300000000000000009997699972000000000099999999997000000000999999999993000000002371000299990000000000000000699930000000000000000999300000000000000009993000000466600000099930000006999200004999300000029997000089990000000079999669999300000000079999999950000000000037999993000000",
        '6' to "0000000026899930000000000004899999300000000000599999531000000000039997200000000000002899710000000000000059993000000000000000699600464000000000039995799998400000000399999999999500000003999995359998200000059997100049995000000699930000179960000004999300000699600000039993000006996000000399930000069960000001799600002899600000004999500059993000000017999768999600000000017999999971000000000015999995100000",
        '7' to "0009999999999999900000099999999999999000000999999999999990000000000000000999300000000000000069990000000000000000999300000000000000069991000000000000000899500000000000000049991000000000000000899500000000000000049993000000000000000899900000000000000009993000000000000000699900000000000000009995000000000000000699910000000000000009995000000000000000699910000000000000009999000000000000000499930000000000",
        '8' to "0000004899998400000000000699999999820000000059999559999500000000699710017998200000039996000049993000000399930000399930000003999600005999300000006998200289960000000017999779997100000000017999999710000000000599999999500000000059995335999500000003999400004999300000069993000039996000000699820000069960000004999300003999600000039998200059993000000069999779999600000000179999999971000000000027999994000000",
        '9' to "0000004899997200000000000599999998200000000059999579998200000002899710039995000000039993000049982000000699710000399930000006996000003999300000069960000039995000000699930000399960000003999500005999400000017999500599993000000049999999999930000000049999997999300000000013333159971000000000000000699600000000000000059971000000000000027999400000000000468999940000000000006999994000000000000069995100000000"
    )
    internal val TPL: Map<Char, FloatArray> = RAW.mapValues { (_, s) -> FloatArray(s.length) { s[it].digitToInt() / 9f } }

    private fun f(x: Double) = floor(x).toInt()

    fun read(gIn: FloatArray, W: Int, H: Int): Grid? {
        val g = gIn.copyOf()
        val hist = IntArray(256)
        for (p in g) hist[p.toInt().coerceIn(0, 255)]++
        var acc = 0; var bg = 0
        for (k in 0 until 256) { acc += hist[k]; if (acc >= g.size / 2) { bg = k; break } }
        if (bg < 128) { for (i in g.indices) g[i] = 255f - g[i]; bg = 255 - bg }
        val dark = BooleanArray(g.size) { g[it] < bg - 25 }
        val ink = BooleanArray(g.size) { g[it] < bg - 150 }

        val colRun = IntArray(W); val rowRun = IntArray(H)
        val cur = IntArray(W)
        for (y in 0 until H) for (x in 0 until W) {
            cur[x] = if (dark[y * W + x]) cur[x] + 1 else 0
            if (cur[x] > colRun[x]) colRun[x] = cur[x]
        }
        for (y in 0 until H) { var c = 0; for (x in 0 until W) { c = if (dark[y * W + x]) c + 1 else 0; if (c > rowRun[y]) rowRun[y] = c } }

        val cmax = colRun.maxOrNull() ?: return null
        val X = prog(clusters(colRun, max(0.2 * cmax, 60.0)), 0.0)
        if (X.size < 5 || X.size > 11) return null
        val n = X.size - 1
        val d = (X[n] - X[0]) / n
        val Y = prog(clusters(rowRun, 0.6 * (X[n] - X[0])), d).take(n + 1)
        if (Y.size != n + 1) return null
        val x0 = X[0]; val y0 = Y[0]

        fun inkAt(x: Int, y: Int) = x in 0 until W && y in 0 until H && ink[y * W + x]
        fun frac(xa: Double, xb: Double, ya: Double, yb: Double): Double {
            var t = 0; var c = 0
            for (y in f(ya)..f(yb)) for (x in f(xa)..f(xb)) { c++; if (inkAt(x, y)) t++ }
            return if (c > 0) t.toDouble() / c else 0.0
        }
        val h = Array(n) { BooleanArray(n) }; val v = Array(n) { BooleanArray(n) }
        for (r in 0 until n) for (c in 0 until n) {
            if (c < n - 1) { val xe = x0 + (c + 1) * d; v[r][c] = frac(xe - 0.05 * d, xe + 0.05 * d, y0 + r * d + 0.3 * d, y0 + r * d + 0.7 * d) > 0.6 }
            if (r < n - 1) { val ye = y0 + (r + 1) * d; h[r][c] = frac(x0 + c * d + 0.3 * d, x0 + c * d + 0.7 * d, ye - 0.05 * d, ye + 0.05 * d) > 0.6 }
        }
        val nums = IntArray(n * n); var unread = 0
        for (r in 0 until n) for (c in 0 until n) {
            val cx = x0 + (c + .5) * d; val cy = y0 + (r + .5) * d
            var hits = 0
            for (k in 0 until 24) { val a = k / 24.0 * 2 * PI; if (inkAt(f(cx + 0.27 * d * cos(a)), f(cy + 0.27 * d * sin(a)))) hits++ }
            if (hits < 18) continue
            val s = glyphs(g, W, H, bg, cx, cy, d).joinToString("") { feat ->
                var bk = '?'; var bs = Float.MAX_VALUE
                for ((k, t) in TPL) { var e = 0f; for (i in 0 until G * G) { val q = feat[i] - t[i]; e += q * q }; if (e < bs) { bs = e; bk = k } }
                bk.toString()
            }
            val value = s.toIntOrNull() ?: 0
            if (value > 0) nums[r * n + c] = value else unread++
        }
        return Grid(n, h, v, nums, unread, x0, y0, d)
    }

    private fun clusters(arr: IntArray, thr: Double): List<Double> {
        val cs = ArrayList<Double>(); var s = -1; var p = -1
        for (i in arr.indices) if (arr[i] > thr) {
            if (s < 0) { s = i; p = i } else if (i == p + 1) p = i else { cs.add((s + p) / 2.0); s = i; p = i }
        }
        if (s >= 0) cs.add((s + p) / 2.0)
        return cs
    }

    private fun prog(cs: List<Double>, d0: Double): List<Double> {
        var best: List<Double> = emptyList()
        for (i in cs.indices) for (j in i + 1 until cs.size) {
            val dd = cs[j] - cs[i]
            if (dd < 8) continue
            if (d0 > 0 && abs(dd - d0) > 0.06 * d0) continue
            val ch = arrayListOf(cs[i], cs[j])
            while (true) { val nx = cs.firstOrNull { abs(it - (ch.last() + dd)) < 0.07 * dd } ?: break; ch.add(nx) }
            if (ch.size > best.size || (ch.size == best.size && dd > best[1] - best[0])) best = ch
        }
        return best
    }

    private fun glyphs(g: FloatArray, W: Int, H: Int, bg: Int, cx: Double, cy: Double, d: Double): List<FloatArray> {
        val R = 0.24 * d
        val xa = f(cx - R); val xb = f(cx + R); val ya = f(cy - R); val yb = f(cy + R)
        val ww = xb - xa + 1; val hh = yb - ya + 1
        val m = BooleanArray(ww * hh); val lab = IntArray(ww * hh) { -1 }
        for (y in 0 until hh) for (x in 0 until ww) {
            val X = xa + x; val Y = ya + y
            m[y * ww + x] = X in 0 until W && Y in 0 until H && g[Y * W + X] > bg - 90 &&
                (X - cx) * (X - cx) + (Y - cy) * (Y - cy) <= R * R
        }
        class Box(var x0: Int, var x1: Int, var y0: Int, var y1: Int, val ids: MutableList<Int>, val cnt: Int)
        val comps = ArrayList<Box>()
        val st = ArrayDeque<Int>()
        for (i in 0 until ww * hh) if (m[i] && lab[i] < 0) {
            val id = comps.size; lab[i] = id; st.addLast(i)
            var cnt = 0; var mnx = Int.MAX_VALUE; var mxx = -1; var mny = Int.MAX_VALUE; var mxy = -1
            while (st.isNotEmpty()) {
                val p = st.removeLast(); val px = p % ww; val py = p / ww; cnt++
                mnx = min(mnx, px); mxx = max(mxx, px); mny = min(mny, py); mxy = max(mxy, py)
                val nb = intArrayOf(if (px + 1 < ww) p + 1 else -1, if (px > 0) p - 1 else -1, if (py + 1 < hh) p + ww else -1, if (py > 0) p - ww else -1)
                for (q in nb) if (q >= 0 && m[q] && lab[q] < 0) { lab[q] = id; st.addLast(q) }
            }
            comps.add(Box(mnx, mxx, mny, mxy, mutableListOf(id), cnt))
        }
        if (comps.isEmpty()) return emptyList()
        val mx = comps.maxOf { it.cnt }
        val boxes = comps.filter { it.cnt >= 0.15 * mx }.sortedBy { it.x0 }
        val merged = ArrayList<Box>()
        for (b in boxes) {
            val l = merged.lastOrNull()
            if (l != null && b.x0 <= l.x1) { l.x1 = max(l.x1, b.x1); l.y0 = min(l.y0, b.y0); l.y1 = max(l.y1, b.y1); l.ids.addAll(b.ids) }
            else merged.add(Box(b.x0, b.x1, b.y0, b.y1, b.ids.toMutableList(), b.cnt))
        }
        return merged.map { b ->
            val set = b.ids.toHashSet()
            val hgt = b.y1 - b.y0 + 1; val wid = b.x1 - b.x0 + 1
            val sc = G.toDouble() / hgt; val ox = (G - wid * sc) / 2
            val out = FloatArray(G * G)
            for (ty in 0 until G) for (tx in 0 until G) {
                var a = 0
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val px = (tx + (sx + .5) / 3 - ox) / sc; val py = (ty + (sy + .5) / 3) / sc
                    if (px >= 0 && px < wid && py >= 0 && py < hgt && lab[(b.y0 + f(py)) * ww + b.x0 + f(px)] in set) a++
                }
                out[ty * G + tx] = a / 9f
            }
            out
        }
    }
}
