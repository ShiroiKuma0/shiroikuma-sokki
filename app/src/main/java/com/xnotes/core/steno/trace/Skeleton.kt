package com.xnotes.core.steno.trace

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Zhang–Suen thinning: the one-pixel centre line of the ink. */
object Thinning {

    fun thin(m: Mask): Mask {
        val w = m.w
        val h = m.h
        val a = m.on.copyOf()
        fun p(x: Int, y: Int) = if (x in 0 until w && y in 0 until h && a[y * w + x]) 1 else 0
        val del = ArrayList<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (pass in 0..1) {
                del.clear()
                for (y in 0 until h) for (x in 0 until w) {
                    if (!a[y * w + x]) continue
                    val p2 = p(x, y - 1); val p3 = p(x + 1, y - 1); val p4 = p(x + 1, y); val p5 = p(x + 1, y + 1)
                    val p6 = p(x, y + 1); val p7 = p(x - 1, y + 1); val p8 = p(x - 1, y); val p9 = p(x - 1, y - 1)
                    val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                    if (b < 2 || b > 6) continue
                    val seq = intArrayOf(p2, p3, p4, p5, p6, p7, p8, p9, p2)
                    var t = 0
                    for (i in 0 until 8) if (seq[i] == 0 && seq[i + 1] == 1) t++
                    if (t != 1) continue
                    if (pass == 0) {
                        if (p2 * p4 * p6 != 0 || p4 * p6 * p8 != 0) continue
                    } else {
                        if (p2 * p4 * p8 != 0 || p2 * p6 * p8 != 0) continue
                    }
                    del += y * w + x
                }
                if (del.isNotEmpty()) changed = true
                for (i in del) a[i] = false
            }
        }
        return Mask(w, h, a)
    }

    /** Count of skeleton neighbours of pixel ([x], [y]). */
    internal fun degree(m: Mask, x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && m[x + dx, y + dy]) n++
        return n
    }

    /** Without side branches shorter than [maxLen] pixels that end free: the print's specks and
     *  ragged edges grow such spurs, and a route would wander into them. */
    fun pruneSpurs(sk: Mask, maxLen: Int): Mask {
        val m = sk.copy()
        repeat(3) {
            var removed = false
            for (y in 0 until m.h) for (x in 0 until m.w) {
                if (!m[x, y] || degree(m, x, y) != 1) continue
                val path = ArrayList<Int>()
                var cx = x
                var cy = y
                var prev = -1
                var reachedJunction = false
                while (path.size <= maxLen) {
                    path += cy * m.w + cx
                    var next = -1
                    var count = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (!m[nx, ny]) continue
                        val q = ny * m.w + nx
                        if (q == prev || q in path) continue
                        count++
                        next = q
                    }
                    if (count != 1) break
                    prev = cy * m.w + cx
                    cx = next % m.w
                    cy = next / m.w
                    if (degree(m, cx, cy) >= 3) {
                        reachedJunction = true
                        break
                    }
                }
                if (reachedJunction && path.size < maxLen) {
                    for (q in path) m.on[q] = false
                    removed = true
                }
            }
            if (!removed) return m
        }
        return m
    }
}

/**
 * The print's centre line as a graph, and the route a trace takes through it.
 *
 * This is map matching, as GPS tracks are matched to roads: each trace point has a handful of
 * candidate centre-line pixels near it, and a Viterbi pass picks the sequence that stays close to
 * the trace (emission) while moving along the ink as far as the pen moved (transition, measured on
 * the graph). The route can only ever lie on the printed line and in the pen's order, so the
 * hand's wobble, its misses and a loop traced too wide all fall away.
 */
class SkeletonGraph(private val sk: Mask) {

    private val w = sk.w
    private val index = IntArray(sk.on.size) { -1 }
    private val xs: IntArray
    private val ys: IntArray

    init {
        var n = 0
        for (i in sk.on.indices) if (sk.on[i]) index[i] = n++
        xs = IntArray(n)
        ys = IntArray(n)
        for (i in sk.on.indices) if (sk.on[i]) {
            xs[index[i]] = i % w
            ys[index[i]] = i / w
        }
    }

    val size: Int get() = xs.size

    fun x(i: Int) = xs[i].toDouble()
    fun y(i: Int) = ys[i].toDouble()

    /** Nodes within [r] of ([px], [py]), nearest first, no two closer than 3 px, at most [k]. */
    fun near(px: Double, py: Double, r: Double, k: Int): List<Int> {
        val out = ArrayList<Pair<Double, Int>>()
        val x0 = (px - r).toInt().coerceAtLeast(0)
        val x1 = (px + r).toInt().coerceAtMost(sk.w - 1)
        val y0 = (py - r).toInt().coerceAtLeast(0)
        val y1 = (py + r).toInt().coerceAtMost(sk.h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val i = index[y * w + x]
            if (i < 0) continue
            val d = hypot(x - px, y - py)
            if (d <= r) out += d to i
        }
        out.sortBy { it.first }
        val sel = ArrayList<Int>()
        for ((_, i) in out) {
            if (sel.all { hypot(x(it) - x(i), y(it) - y(i)) >= 3.0 }) sel += i
            if (sel.size >= k) break
        }
        return sel
    }

    /** The nearest node to ([px], [py]) within [r], or -1. */
    fun nearest(px: Double, py: Double, r: Double): Int = near(px, py, r, 1).firstOrNull() ?: -1

    /** Shortest distances along the skeleton from [src], up to [cut]; with predecessors. */
    fun reach(src: Int, cut: Double): Pair<HashMap<Int, Double>, HashMap<Int, Int>> {
        val dist = HashMap<Int, Double>()
        val prev = HashMap<Int, Int>()
        dist[src] = 0.0
        val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        q += 0.0 to src
        while (q.isNotEmpty()) {
            val (d, u) = q.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE) || d > cut) continue
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = xs[u] + dx
                val ny = ys[u] + dy
                if (nx !in 0 until sk.w || ny !in 0 until sk.h) continue
                val v = index[ny * w + nx]
                if (v < 0) continue
                val nd = d + if (dx != 0 && dy != 0) SQRT2 else 1.0
                if (nd < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nd
                    prev[v] = u
                    q += nd to v
                }
            }
        }
        return dist to prev
    }

    /**
     * The route of [trace] (points in raster px, already resampled at about [stepPx]) through the
     * centre line, as raster points. [radius] bounds how far the print may lie from the pen;
     * [sigma] is the expected pen-to-print distance.
     */
    fun route(trace: List<DoubleArray>, stepPx: Double, radius: Double, sigma: Double, detourLimit: Int, extendMax: Double = 0.0): List<DoubleArray> {
        if (size == 0 || trace.isEmpty()) return emptyList()
        val cand = trace.map { p ->
            near(p[0], p[1], radius, 14).ifEmpty { listOf(nearestAnywhere(p[0], p[1])) }
        }
        val emis = cand.mapIndexed { t, c ->
            DoubleArray(c.size) { val d = hypot(x(c[it]) - trace[t][0], y(c[it]) - trace[t][1]); d * d / (2 * sigma * sigma) }
        }
        val cut = 6 * stepPx + 2 * radius
        val cache = HashMap<Int, Pair<HashMap<Int, Double>, HashMap<Int, Int>>>()
        var cost = emis[0].copyOf()
        val back = ArrayList<IntArray>()
        for (t in 1 until trace.size) {
            val dt = hypot(trace[t][0] - trace[t - 1][0], trace[t][1] - trace[t - 1][1])
            val next = DoubleArray(cand[t].size) { Double.POSITIVE_INFINITY }
            val bk = IntArray(cand[t].size)
            for ((a, ia) in cand[t - 1].withIndex()) {
                if (!cost[a].isFinite()) continue
                val dist = cache.getOrPut(ia) { reach(ia, cut) }.first
                for ((b, ib) in cand[t].withIndex()) {
                    val g = dist[ib] ?: continue
                    val c = cost[a] + abs(g - dt) / (0.5 * stepPx) + emis[t][b]
                    if (c < next[b]) {
                        next[b] = c
                        bk[b] = a
                    }
                }
            }
            if (next.none { it.isFinite() }) {
                // Broken ink: let the route jump, at a price, rather than fail.
                val best = cost.indices.minByOrNull { cost[it] } ?: 0
                for (b in next.indices) {
                    next[b] = cost[best] + emis[t][b] + 50.0
                    bk[b] = best
                }
            }
            back += bk
            cost = next
        }
        var j = cost.indices.minByOrNull { cost[it] } ?: 0
        val seq = IntArray(trace.size)
        seq[trace.size - 1] = cand[trace.size - 1][j]
        for (t in trace.size - 1 downTo 1) {
            j = back[t - 1][j]
            seq[t - 1] = cand[t - 1][j]
        }
        // Expand to the full pixel path.
        var path = ArrayList<Int>()
        path += seq[0]
        for (k in 1 until seq.size) {
            val a = seq[k - 1]
            val b = seq[k]
            if (a == b) continue
            val prev = cache.getOrPut(a) { reach(a, cut) }.second
            if (b !in prev) {
                path += b
                continue
            }
            val r = ArrayList<Int>()
            var u: Int? = b
            while (u != null && u != a) {
                r += u
                u = prev[u]
            }
            r.reverse()
            path.addAll(r)
        }
        path = cutDetours(path, detourLimit)
        // The pen often stops short of the printed end: run on along the print while it continues
        // the same way, up to [extendMax].
        path = extend(path, extendMax)
        path.reverse()
        path = extend(path, extendMax)
        path.reverse()
        return path.map { doubleArrayOf(x(it), y(it)) }
    }

    /** [path] continued from its last node along the skeleton while each step keeps within ~35°
     *  of the path's closing direction, for at most [maxLen] px; it stops at a fork it cannot
     *  decide straight through, and at the skeleton's end. */
    private fun extend(path: ArrayList<Int>, maxLen: Double): ArrayList<Int> {
        if (maxLen <= 0 || path.size < 6) return path
        val back = path[maxOf(0, path.size - 9)]
        var dx = x(path.last()) - x(back)
        var dy = y(path.last()) - y(back)
        val n0 = hypot(dx, dy)
        if (n0 == 0.0) return path
        dx /= n0
        dy /= n0
        val visited = path.toHashSet()
        var len = 0.0
        var cur = path.last()
        val added = ArrayList<Int>()
        while (len < maxLen) {
            var best = -1
            var bestCos = 0.82
            for (oy in -1..1) for (ox in -1..1) {
                if (ox == 0 && oy == 0) continue
                val nx = xs[cur] + ox
                val ny = ys[cur] + oy
                if (nx !in 0 until sk.w || ny !in 0 until sk.h) continue
                val v = index[ny * w + nx]
                if (v < 0 || v in visited) continue
                val c = (ox * dx + oy * dy) / hypot(ox.toDouble(), oy.toDouble())
                if (c > bestCos) {
                    bestCos = c
                    best = v
                }
            }
            if (best < 0) break
            len += hypot(x(best) - x(cur), y(best) - y(cur))
            visited += best
            added += best
            cur = best
            // Follow the print's own direction, slowly, so a gentle curve can still be met.
            val anchor = if (added.size >= 8) added[added.size - 8] else path[maxOf(0, path.size - 8 + added.size)]
            val ndx = x(cur) - x(anchor)
            val ndy = y(cur) - y(anchor)
            val nn = hypot(ndx, ndy)
            if (nn > 0) {
                dx = 0.7 * dx + 0.3 * ndx / nn
                dy = 0.7 * dy + 0.3 * ndy / nn
                val m = hypot(dx, dy)
                dx /= m
                dy /= m
            }
        }
        path.addAll(added)
        return path
    }

    private fun nearestAnywhere(px: Double, py: Double): Int {
        var best = 0
        var bd = Double.MAX_VALUE
        for (i in 0 until size) {
            val d = hypot(x(i) - px, y(i) - py)
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    companion object {
        private val SQRT2 = sqrt(2.0)

        /** Out-and-back excursions (into a dash or a spur and back the same way) removed: a node
         *  visited twice within [limit] steps closes a loop that the pen never drew. */
        fun cutDetours(path: List<Int>, limit: Int): ArrayList<Int> {
            var p = ArrayList(path)
            var changed = true
            while (changed) {
                changed = false
                val seen = HashMap<Int, Int>()
                for ((k, v) in p.withIndex()) {
                    val s = seen[v]
                    if (s != null && k - s in 2..limit) {
                        val q = ArrayList<Int>(p.subList(0, s + 1))
                        q.addAll(p.subList(k + 1, p.size))
                        p = q
                        changed = true
                        break
                    }
                    seen[v] = k
                }
            }
            return p
        }
    }
}
