package com.natrasta.inzipsolver

/** Trouve le chemin qui part du 1, passe les chiffres dans l'ordre, couvre toutes les cases et ne traverse aucun mur. */
object ZipSolver {
    class Result(val path: IntArray?, val error: String?)

    fun solve(grid: ZipReader.Grid): Result {
        val n = grid.n; val total = n * n; val num = grid.nums
        val pos = HashMap<Int, Int>(); var k = 0
        for (i in 0 until total) if (num[i] > 0) {
            if (pos.containsKey(num[i])) return Result(null, "Le ${num[i]} apparaît deux fois.")
            pos[num[i]] = i; k = maxOf(k, num[i])
        }
        if (k < 2) return Result(null, "Il faut au moins deux chiffres.")
        for (x in 1..k) if (!pos.containsKey(x)) return Result(null, "Il manque le $x.")

        val adj = Array(total) { ArrayList<Int>(4) }
        for (r in 0 until n) for (c in 0 until n) {
            val i = r * n + c
            if (c < n - 1 && !grid.v[r][c]) { adj[i].add(i + 1); adj[i + 1].add(i) }
            if (r < n - 1 && !grid.h[r][c]) { adj[i].add(i + n); adj[i + n].add(i) }
        }
        val start = pos[1]!!; val end = pos[k]!!
        val vis = BooleanArray(total)
        val path = IntArray(total); var len = 1
        path[0] = start; vis[start] = true
        var nodes = 0L
        var limit = 0L
        var rnd = java.util.Random(1)
        val tie = DoubleArray(total)
        // damier : le chemin alterne forcément les deux couleurs de cases
        val color = IntArray(total) { (it / n + it % n) % 2 }
        val left = IntArray(2); for (i in 0 until total) left[color[i]]++
        left[color[start]]--

        // Points de coupure (Tarjan) sur les cases libres + la tête : chaque case dont le retrait isole une zone
        // sans la case d'arrivée rend le chemin impossible, car on ne pourrait plus en ressortir.
        val disc = IntArray(total); val low = IntArray(total)
        fun cuts(head: Int, remaining: Int): Boolean {
            java.util.Arrays.fill(disc, 0)
            var time = 0; var reached = 0; var fail = false
            val seenColor = IntArray(2)
            fun dfsT(u: Int, parent: Int) {
                disc[u] = ++time; low[u] = time; reached++; seenColor[color[u]]++
                var separated = 0; var children = 0
                for (w in adj[u]) {
                    if (fail) return
                    if (vis[w] && w != head) continue
                    if (disc[w] == 0) {
                        children++
                        val b0 = seenColor[0]; val b1 = seenColor[1]
                        dfsT(w, u)
                        if (low[w] < low[u]) low[u] = low[w]
                        if (u != head && low[w] >= disc[u]) {
                            // la zone sous w ne tient qu'à u : elle doit contenir l'arrivée, et une seule zone de ce genre
                            if (u == end || disc[end] < disc[w] || disc[end] == 0) { fail = true; return }
                            separated++
                            if (separated > 1) { fail = true; return }
                            // la zone est parcourue d'un seul tenant depuis u jusqu'à l'arrivée : même règle du damier
                            val z0 = seenColor[0] - b0; val z1 = seenColor[1] - b1; val m = z0 + z1
                            val ou = 1 - color[u]; val zOpp = if (ou == 0) z0 else z1; val zSame = m - zOpp
                            if (zOpp != (m + 1) / 2 || zSame != m / 2 || color[end] != (if (m % 2 == 1) ou else color[u])) { fail = true; return }
                        }
                    } else if (w != parent && disc[w] < low[u]) low[u] = disc[w]
                }
                if (u == head && children > 1) fail = true
            }
            dfsT(head, -1)
            return !fail && reached == remaining + 1
        }

        // le prochain chiffre doit rester atteignable sans traverser un chiffre plus grand
        val seen = BooleanArray(total); val queue = IntArray(total)
        fun nextReachable(head: Int, next: Int): Boolean {
            if (next > k) return true
            val target = pos[next]!!
            java.util.Arrays.fill(seen, false)
            var qh = 0; var qt = 0; seen[head] = true; queue[qt++] = head
            while (qh < qt) {
                val x = queue[qh++]
                for (j in adj[x]) if (!vis[j] && !seen[j]) {
                    if (j == target) return true
                    if (num[j] > next) continue
                    seen[j] = true; queue[qt++] = j
                }
            }
            return false
        }

        fun ok(): Boolean {
            val head = path[len - 1]; val remaining = total - len
            if (remaining == 0) return true
            val hc = color[head]; val opp = 1 - hc
            if (left[opp] != (remaining + 1) / 2 || left[hc] != remaining / 2) return false
            if (!vis[end] && color[end] != (if (remaining % 2 == 1) opp else hc)) return false
            for (i in 0 until total) if (!vis[i]) {
                var d = 0
                for (j in adj[i]) if (!vis[j] || j == head) d++
                if (d < (if (i == end) 1 else 2)) return false
            }
            return cuts(head, remaining)
        }

        fun canEnter(c: Int, next: Int): Boolean {
            if (vis[c]) return false
            val v = num[c]
            if (v > 0) { if (v != next) return false; if (v == k && len != total - 1) return false }
            else if (len == total - 1) return false
            return true
        }

        fun dfs(head: Int, next: Int): Boolean {
            if (len == total) return true
            if (++nodes > limit) throw IllegalStateException("limit")
            val cands = adj[head].filter { canEnter(it, next) }
                .sortedWith(compareBy<Int>({ nb -> adj[nb].count { !vis[it] } }, { tie[it] }))
            for (nb in cands) {
                vis[nb] = true; path[len++] = nb; left[color[nb]]--
                val nn = if (num[nb] > 0) next + 1 else next
                if (ok() && nextReachable(nb, nn) && dfs(nb, nn)) return true
                len--; vis[nb] = false; left[color[nb]]++
            }
            return false
        }

        // Plusieurs essais avec un ordre d'exploration différent : évite de s'enliser dans une mauvaise branche.
        if (!ok()) return Result(null, "Aucun chemin possible avec cette lecture.")
        var budget = 6_000_000L; var tryLimit = 60_000L; var attempt = 0
        while (budget > 0) {
            for (i in 0 until total) tie[i] = if (attempt == 0) 0.0 else rnd.nextDouble()
            java.util.Arrays.fill(vis, false); vis[start] = true; len = 1
            left[0] = 0; left[1] = 0; for (i in 0 until total) left[color[i]]++; left[color[start]]--
            nodes = 0; limit = minOf(tryLimit, budget)
            try {
                if (dfs(start, 2)) return Result(path, null)
                return Result(null, "Aucun chemin possible avec cette lecture.")   // recherche complète sans solution
            } catch (e: IllegalStateException) { }
            budget -= nodes; tryLimit = tryLimit * 3 / 2; attempt++
        }
        return Result(null, "Calcul trop long.")
    }
}
