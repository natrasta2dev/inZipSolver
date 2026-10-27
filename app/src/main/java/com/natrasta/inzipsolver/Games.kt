package com.natrasta.inzipsolver

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Solution prête à dessiner. Les coordonnées sont celles de la capture réduite. */
sealed class Solution(val game: String) {
    class Zip(val n: Int, val path: IntArray, val x0: Double, val y0: Double, val d: Double) : Solution("Zip")
    class Queens(val geo: Board.Geo, val queens: IntArray) : Solution("Queens")             // colonne de la reine par ligne
    class Sudoku(val geo: Board.Geo, val values: IntArray, val given: BooleanArray) : Solution("Mini Sudoku")
    class Tango(val geo: Board.Geo, val values: IntArray, val given: BooleanArray) : Solution("Tango") // 1 soleil, 2 lune
    class Patches(val geo: Board.Geo, val rects: List<Rect>) : Solution("Patches")
    data class Rect(val r0: Int, val c0: Int, val h: Int, val w: Int, val color: Int)
}

class Outcome(val solution: Solution?, val message: String)

object Games {

    fun solve(s: Screen): Outcome {
        // Zip : cercles noirs numérotés, lus par son lecteur dédié
        val zip = ZipReader.read(s.gray(), s.w, s.h)
        if (zip != null && zip.nums.count { it > 0 } >= 2) {
            if (zip.unread > 0) return Outcome(null, "Zip : un chiffre n'a pas pu être lu.")
            val r = ZipSolver.solve(zip)
            return if (r.path != null) Outcome(Solution.Zip(zip.n, r.path, zip.x0, zip.y0, zip.d), "Zip résolu")
                   else Outcome(null, "Zip lu, mais ${r.error}")
        }
        val geo = Board.find(s) ?: return Outcome(null, "Aucune grille reconnue. Affiche toute la grille à l'écran.")
        var colorful = 0; var beige = 0; var strong = 0; var tot = 0
        for (y in geo.top..geo.bottom) for (x in geo.left..geo.right) {
            val i = y * s.w + x; tot++
            val sa = s.sat(i); val v = s.value(i); val l = s.lum(i)
            if (sa > 0.2f && v > 120) colorful++
            if (sa > 0.45f && v > 100) strong++
            if (l > 220 && l < 244 && s.r(i) - s.b(i) > 4 && sa < 0.1f) beige++
        }
        return when {
            colorful > 0.4 * tot -> queens(s, geo)
            beige > 0.1 * tot -> tango(s, geo)
            strong > 0.01 * tot -> patches(s, geo)
            else -> sudoku(s, geo)
        }
    }

    private fun f(x: Double) = floor(x).toInt()

    /* ---------------- Queens ---------------- */
    private fun queens(s: Screen, g: Board.Geo): Outcome {
        val n = g.n; val colors = ArrayList<IntArray>(); val reg = IntArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in f(g.y0 + (r + 0.12) * g.ch) until f(g.y0 + (r + 0.3) * g.ch))
                for (x in f(g.x0 + (c + 0.12) * g.cw) until f(g.x0 + (c + 0.3) * g.cw)) {
                    val i = y * s.w + x; rs.add(s.r(i)); gs.add(s.g(i)); bs.add(s.b(i))
                }
            if (rs.isEmpty()) return Outcome(null, "Queens : grille mal lue.")
            val col = intArrayOf(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
            var idx = colors.indexOfFirst { k -> val a = k[0] - col[0]; val b = k[1] - col[1]; val d = k[2] - col[2]; a * a + b * b + d * d < 28 * 28 }
            if (idx < 0) { colors.add(col); idx = colors.size - 1 }
            reg[r * n + c] = idx
        }
        if (colors.size != n) return Outcome(null, "Queens : ${colors.size} zones de couleur lues au lieu de $n.")
        val q = IntArray(n) { -1 }; val colUsed = BooleanArray(n); val regUsed = BooleanArray(n)
        fun bt(r: Int): Boolean {
            if (r == n) return true
            for (c in 0 until n) {
                val rg = reg[r * n + c]
                if (colUsed[c] || regUsed[rg]) continue
                if (r > 0 && Math.abs(q[r - 1] - c) <= 1) continue
                q[r] = c; colUsed[c] = true; regUsed[rg] = true
                if (bt(r + 1)) return true
                colUsed[c] = false; regUsed[rg] = false
            }
            return false
        }
        return if (bt(0)) Outcome(Solution.Queens(g, q), "Queens résolu") else Outcome(null, "Queens : aucune solution avec cette lecture.")
    }

    /* ---------------- Mini Sudoku ---------------- */
    private fun sudoku(s: Screen, g: Board.Geo): Outcome {
        val n = g.n
        val (bh, bw) = when (n) { 4 -> 2 to 2; 6 -> 2 to 3; 9 -> 3 to 3; else -> return Outcome(null, "Grille de $n × $n non reconnue.") }
        val v = IntArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            val xa = f(g.x0 + (c + 0.14) * g.cw); val xb = f(g.x0 + (c + 0.86) * g.cw)
            val ya = f(g.y0 + (r + 0.14) * g.ch); val yb = f(g.y0 + (r + 0.86) * g.ch)
            val ww = xb - xa; val hh = yb - ya
            val m = BooleanArray(ww * hh) { k -> s.lum((ya + k / ww) * s.w + xa + k % ww) < 110 }
            if (m.count { it } < 0.01 * m.size) continue
            val d = Glyph.number(m, ww, hh, Glyph.SUDOKU)
            if (d !in 1..n) return Outcome(null, "Mini Sudoku : un chiffre n'a pas pu être lu.")
            v[r * n + c] = d
        }
        val given = BooleanArray(n * n) { v[it] > 0 }
        fun ok(i: Int, d: Int): Boolean {
            val r = i / n; val c = i % n
            for (k in 0 until n) { if (v[r * n + k] == d || v[k * n + c] == d) return false }
            val r0 = r / bh * bh; val c0 = c / bw * bw
            for (y in r0 until r0 + bh) for (x in c0 until c0 + bw) if (v[y * n + x] == d) return false
            return true
        }
        for (i in 0 until n * n) if (v[i] > 0) { val d = v[i]; v[i] = 0; if (!ok(i, d)) return Outcome(null, "Mini Sudoku : la grille lue contient un doublon."); v[i] = d }
        fun bt(i: Int): Boolean {
            if (i == n * n) return true
            if (v[i] > 0) return bt(i + 1)
            for (d in 1..n) if (ok(i, d)) { v[i] = d; if (bt(i + 1)) return true; v[i] = 0 }
            return false
        }
        return if (bt(0)) Outcome(Solution.Sudoku(g, v, given), "Mini Sudoku résolu") else Outcome(null, "Mini Sudoku : aucune solution avec cette lecture.")
    }

    /* ---------------- Tango ---------------- */
    private fun tango(s: Screen, g: Board.Geo): Outcome {
        val n = g.n
        if (n % 2 != 0) return Outcome(null, "Tango : grille de $n × $n inattendue.")
        val v = IntArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            var sun = 0; var moon = 0; var tot = 0
            for (y in f(g.y0 + (r + 0.25) * g.ch) until f(g.y0 + (r + 0.75) * g.ch))
                for (x in f(g.x0 + (c + 0.25) * g.cw) until f(g.x0 + (c + 0.75) * g.cw)) {
                    val i = y * s.w + x; val R = s.r(i); val G = s.g(i); val B = s.b(i); tot++
                    if (R > 200 && G in 121..214 && B < 100) sun++
                    if (B > 170 && R < 140 && G < 190) moon++
                }
            v[r * n + c] = if (sun > 0.05 * tot) 1 else if (moon > 0.03 * tot) 2 else 0
        }
        // signes = et × posés sur les bords entre deux cases : 1 = identiques, -1 = différents
        fun sign(cx: Double, cy: Double): Int {
            val hw = 0.13 * g.cw; var cnt = 0; var tot = 0; var mid = 0; var midTot = 0
            for (y in f(cy - hw)..f(cy + hw)) for (x in f(cx - hw)..f(cx + hw)) {
                if (x < 0 || y < 0 || x >= s.w || y >= s.h) continue
                val i = y * s.w + x; tot++
                val dark = s.lum(i) < 170 && s.sat(i) < 0.25f
                if (dark) cnt++
                if (Math.abs(x - cx) <= 0.04 * g.cw && Math.abs(y - cy) <= 0.04 * g.cw) { midTot++; if (dark) mid++ }
            }
            if (tot == 0 || cnt < 0.015 * tot) return 0
            return if (midTot > 0 && mid > 0.25 * midTot) -1 else 1
        }
        val right = IntArray(n * n); val down = IntArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            if (c < n - 1) right[r * n + c] = sign(g.x0 + (c + 1) * g.cw, g.cy(r))
            if (r < n - 1) down[r * n + c] = sign(g.cx(c), g.y0 + (r + 1) * g.ch)
        }
        val given = BooleanArray(n * n) { v[it] > 0 }
        val half = n / 2
        fun ok(i: Int): Boolean {
            val r = i / n; val c = i % n; val x = v[i]
            var a = 0; var b = 0
            for (k in 0 until n) { if (v[r * n + k] == x) a++; if (v[k * n + c] == x) b++ }
            if (a > half || b > half) return false
            if (c >= 2 && v[i - 1] == x && v[i - 2] == x) return false
            if (c >= 1 && c < n - 1 && v[i - 1] == x && v[i + 1] == x) return false
            if (c < n - 2 && v[i + 1] == x && v[i + 2] == x) return false
            if (r >= 2 && v[i - n] == x && v[i - 2 * n] == x) return false
            if (r >= 1 && r < n - 1 && v[i - n] == x && v[i + n] == x) return false
            if (r < n - 2 && v[i + n] == x && v[i + 2 * n] == x) return false
            fun rel(j: Int, s0: Int): Boolean = s0 == 0 || v[j] == 0 || (if (s0 == 1) v[j] == x else v[j] != x)
            if (c < n - 1 && !rel(i + 1, right[i])) return false
            if (c > 0 && !rel(i - 1, right[i - 1])) return false
            if (r < n - 1 && !rel(i + n, down[i])) return false
            if (r > 0 && !rel(i - n, down[i - n])) return false
            return true
        }
        for (i in 0 until n * n) if (v[i] > 0 && !ok(i)) return Outcome(null, "Tango : la grille lue ne respecte pas les règles.")
        fun bt(i: Int): Boolean {
            if (i == n * n) return true
            if (given[i]) return bt(i + 1)
            for (x in 1..2) { v[i] = x; if (ok(i) && bt(i + 1)) return true }
            v[i] = 0; return false
        }
        return if (bt(0)) Outcome(Solution.Tango(g, v, given), "Tango résolu") else Outcome(null, "Tango : aucune solution avec cette lecture.")
    }

    /* ---------------- Patches ---------------- */
    private class Clue(val r: Int, val c: Int, val area: Int, val shape: Char, val color: Int) // shape : s carré, t haut, w large, a libre

    private fun patches(s: Screen, g: Board.Geo): Outcome {
        val n = g.n; val W = s.w
        val bw = g.right - g.left + 1; val bh = g.bottom - g.top + 1
        val strongM = BooleanArray(bw * bh); val anyM = BooleanArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = (g.top + y) * W + g.left + x
            strongM[y * bw + x] = s.sat(i) > 0.45f && s.value(i) > 100
            anyM[y * bw + x] = s.lum(i) < 140 && s.sat(i) < 0.15f
        }
        val clues = ArrayList<Clue>(); val taken = HashSet<Int>()
        for ((mask, isAny) in listOf(strongM to false, anyM to true)) {
            val lab = IntArray(bw * bh) { -1 }; val st = ArrayDeque<Int>(); var id = 0
            for (start in 0 until bw * bh) if (mask[start] && lab[start] < 0) {
                lab[start] = id; st.addLast(start)
                var cnt = 0; var x0 = bw; var x1 = -1; var y0 = bh; var y1 = -1
                while (st.isNotEmpty()) {
                    val p = st.removeLast(); val px = p % bw; val py = p / bw; cnt++
                    x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py)
                    for (q in intArrayOf(if (px + 1 < bw) p + 1 else -1, if (px > 0) p - 1 else -1, if (py + 1 < bh) p + bw else -1, if (py > 0) p - bw else -1))
                        if (q >= 0 && mask[q] && lab[q] < 0) { lab[q] = id; st.addLast(q) }
                }
                id++
                val w = x1 - x0 + 1; val h = y1 - y0 + 1
                if (cnt < 0.04 * g.cw * g.ch || cnt < 0.6 * w * h) continue
                val ccx = g.left + (x0 + x1) / 2.0; val ccy = g.top + (y0 + y1) / 2.0
                val c = floor((ccx - g.x0) / g.cw).toInt(); val r = floor((ccy - g.y0) / g.ch).toInt()
                if (r !in 0 until n || c !in 0 until n || !taken.add(r * n + c)) continue
                // chiffre blanc dans le badge
                val dm = BooleanArray(w * h) { k -> val i = (g.top + y0 + k / w) * W + g.left + x0 + k % w; s.lum(i) > 200 && s.sat(i) < 0.25f }
                val area = if (dm.count { it } > 0.02 * dm.size) Glyph.number(dm, w, h, ZipReader.TPL) else 0
                val ratio = w.toDouble() / h
                val shape = if (isAny) 'a' else if (ratio > 1.2) 'w' else if (ratio < 0.83) 't' else 's'
                val mid = (g.top + (y0 + y1) / 2) * W + g.left + (x0 + x1) / 2
                clues.add(Clue(r, c, area, shape, if (isAny) 0x555555 else s.px[mid] and 0xFFFFFF))
            }
        }
        if (clues.isEmpty()) return Outcome(null, "Patches : aucun badge trouvé.")
        val owner = IntArray(n * n) { -1 }
        clues.forEachIndexed { k, cl -> owner[cl.r * n + cl.c] = k }
        // rectangles possibles pour chaque badge
        val cands = clues.mapIndexed { k, cl ->
            val list = ArrayList<Solution.Rect>()
            for (h in 1..n) for (w in 1..n) {
                if (cl.area > 0 && h * w != cl.area) continue
                when (cl.shape) { 's' -> if (h != w) continue; 't' -> if (h <= w) continue; 'w' -> if (w <= h) continue }
                for (r0 in cl.r - h + 1..cl.r) for (c0 in cl.c - w + 1..cl.c) {
                    if (r0 < 0 || c0 < 0 || r0 + h > n || c0 + w > n) continue
                    var clean = true
                    loop@ for (y in r0 until r0 + h) for (x in c0 until c0 + w) { val o = owner[y * n + x]; if (o >= 0 && o != k) { clean = false; break@loop } }
                    if (clean) list.add(Solution.Rect(r0, c0, h, w, cl.color))
                }
            }
            list
        }
        if (cands.any { it.isEmpty() }) return Outcome(null, "Patches : un badge n'a aucune forme possible.")
        val cover = IntArray(n * n) { -1 }; val chosen = arrayOfNulls<Solution.Rect>(clues.size)
        fun fits(rc: Solution.Rect): Boolean { for (y in rc.r0 until rc.r0 + rc.h) for (x in rc.c0 until rc.c0 + rc.w) if (cover[y * n + x] >= 0) return false; return true }
        fun mark(rc: Solution.Rect, v: Int) { for (y in rc.r0 until rc.r0 + rc.h) for (x in rc.c0 until rc.c0 + rc.w) cover[y * n + x] = v }
        fun bt(): Boolean {
            val cell = (0 until n * n).firstOrNull { cover[it] < 0 } ?: return chosen.all { it != null }
            val r = cell / n; val c = cell % n
            for (k in clues.indices) if (chosen[k] == null) for (rc in cands[k]) {
                if (r < rc.r0 || r >= rc.r0 + rc.h || c < rc.c0 || c >= rc.c0 + rc.w || !fits(rc)) continue
                chosen[k] = rc; mark(rc, k)
                if (bt()) return true
                mark(rc, -1); chosen[k] = null
            }
            return false
        }
        return if (bt()) Outcome(Solution.Patches(g, chosen.filterNotNull()), "Patches résolu")
               else Outcome(null, "Patches : aucune solution avec cette lecture.")
    }
}
