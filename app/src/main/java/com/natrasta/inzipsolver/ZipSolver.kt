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
        val vis = BooleanArray(total); val seen = BooleanArray(total); val queue = IntArray(total)
        val path = IntArray(total); var len = 1
        path[0] = start; vis[start] = true
        var nodes = 0L

        fun ok(): Boolean {
            val head = path[len - 1]; val remaining = total - len
            if (remaining == 0) return true
            for (i in 0 until total) if (!vis[i]) {
                var d = 0
                for (j in adj[i]) if (!vis[j] || j == head) d++
                if (d < (if (i == end) 1 else 2)) return false
            }
            java.util.Arrays.fill(seen, false)
            var qh = 0; var qt = 0; var cnt = 0
            for (j in adj[head]) if (!vis[j] && !seen[j]) { seen[j] = true; queue[qt++] = j }
            while (qh < qt) {
                val x = queue[qh++]; cnt++
                for (j in adj[x]) if (!vis[j] && !seen[j]) { seen[j] = true; queue[qt++] = j }
            }
            return cnt == remaining
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
            if (++nodes > 8_000_000L) throw IllegalStateException("limit")
            val cands = adj[head].filter { canEnter(it, next) }
                .sortedBy { nb -> adj[nb].count { !vis[it] } }
            for (nb in cands) {
                vis[nb] = true; path[len++] = nb
                if (ok() && dfs(nb, if (num[nb] > 0) next + 1 else next)) return true
                len--; vis[nb] = false
            }
            return false
        }

        return try {
            if (ok() && dfs(start, 2)) Result(path, null)
            else Result(null, "Aucun chemin possible avec cette lecture.")
        } catch (e: IllegalStateException) {
            Result(null, "Calcul trop long.")
        }
    }
}
