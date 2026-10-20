package com.xnotes.core.steno.trace

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A grayscale raster for reading printed shorthand: 0 is black ink, 255 white paper, row-major.
 * Plain arrays, so the whole trace pipeline runs on the JVM in tests exactly as on the phone.
 */
class Gray(val w: Int, val h: Int, val px: FloatArray) {
    init {
        require(px.size == w * h) { "raster is ${px.size}, not $w×$h" }
    }

    operator fun get(x: Int, y: Int): Float = px[y * w + x]

    /** Bilinear value at ([x], [y]), clamped to the edge. */
    fun at(x: Double, y: Double): Float {
        val cx = x.coerceIn(0.0, w - 1.001)
        val cy = y.coerceIn(0.0, h - 1.001)
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val fx = (cx - x0).toFloat()
        val fy = (cy - y0).toFloat()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        return get(x0, y0) * (1 - fx) * (1 - fy) + get(x1, y0) * fx * (1 - fy) +
            get(x0, y1) * (1 - fx) * fy + get(x1, y1) * fx * fy
    }

    /** This raster resampled by [scale] (area-averaged when shrinking, bilinear when growing). */
    fun scaled(scale: Double): Gray {
        val nw = max(1, (w * scale).roundToInt())
        val nh = max(1, (h * scale).roundToInt())
        val out = FloatArray(nw * nh)
        if (scale >= 1.0) {
            for (y in 0 until nh) for (x in 0 until nw) out[y * nw + x] = at((x + 0.5) / scale - 0.5, (y + 0.5) / scale - 0.5)
        } else {
            val inv = 1.0 / scale
            for (y in 0 until nh) {
                val sy0 = (y * inv).toInt()
                val sy1 = min(h, max(sy0 + 1, ((y + 1) * inv).toInt()))
                for (x in 0 until nw) {
                    val sx0 = (x * inv).toInt()
                    val sx1 = min(w, max(sx0 + 1, ((x + 1) * inv).toInt()))
                    var s = 0f
                    for (yy in sy0 until sy1) for (xx in sx0 until sx1) s += px[yy * w + xx]
                    out[y * nw + x] = s / ((sy1 - sy0) * (sx1 - sx0))
                }
            }
        }
        return Gray(nw, nh, out)
    }

    /** Levels stretched so the darkest 0.2% are black and the lightest 2% white: scans differ in
     *  exposure, and the ink threshold is absolute. A crop is mostly paper, so the dark end is
     *  taken from a sliver; a crop with no contrast at all is left as it is. */
    fun normalized(): Gray {
        val hist = IntArray(256)
        for (v in px) hist[v.roundToInt().coerceIn(0, 255)]++
        fun pct(q: Double): Int {
            val target = q * px.size
            var seen = 0
            for (i in 0 until 256) {
                seen += hist[i]
                if (seen >= target) return i
            }
            return 255
        }
        val lo = pct(0.002).toFloat()
        val hi = pct(0.98).toFloat()
        if (hi - lo < 40f) return this
        val k = 255f / (hi - lo)
        return Gray(w, h, FloatArray(px.size) { ((px[it] - lo) * k).coerceIn(0f, 255f) })
    }

    /** Separable Gaussian blur. */
    fun blurred(sigma: Double): Gray {
        if (sigma < 0.3) return this
        val r = (3 * sigma).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)).toFloat() }
        val ks = k.sum()
        for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (j in -r..r) s += k[j + r] * px[y * w + (x + j).coerceIn(0, w - 1)]
            tmp[y * w + x] = s
        }
        val out = FloatArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (j in -r..r) s += k[j + r] * tmp[(y + j).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s
        }
        return Gray(w, h, out)
    }

    /** Ink: pixels darker than [threshold]. */
    fun below(threshold: Float): Mask = Mask(w, h, BooleanArray(px.size) { px[it] < threshold })
}

/** A binary raster. */
class Mask(val w: Int, val h: Int, val on: BooleanArray) {

    operator fun get(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && on[y * w + x]

    fun copy(): Mask = Mask(w, h, on.copyOf())

    /** 8-connected components of [value] pixels, as a label per pixel (-1 elsewhere) and sizes. */
    private fun components(value: Boolean): Pair<IntArray, IntArray> {
        val label = IntArray(on.size) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(on.size)
        for (start in on.indices) {
            if (on[start] != value || label[start] >= 0) continue
            val id = sizes.size
            var top = 0
            var n = 0
            stack[top++] = start
            label[start] = id
            while (top > 0) {
                val p = stack[--top]
                n++
                val px = p % w
                val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val x = px + dx
                    val y = py + dy
                    if (x !in 0 until w || y !in 0 until h) continue
                    val q = y * w + x
                    if (on[q] == value && label[q] < 0) {
                        label[q] = id
                        stack[top++] = q
                    }
                }
            }
            sizes += n
        }
        return label to sizes.toIntArray()
    }

    /** Without ink specks smaller than [minArea] pixels. */
    fun withoutSpecks(minArea: Int): Mask {
        val (label, sizes) = components(true)
        return Mask(w, h, BooleanArray(on.size) { on[it] && sizes[label[it]] >= minArea })
    }

    /** With holes smaller than [maxArea] filled (a hole touching the border is never one). */
    fun withHolesFilled(maxArea: Int): Mask {
        val (label, sizes) = components(false)
        val border = HashSet<Int>()
        for (x in 0 until w) {
            if (!on[x]) border += label[x]
            if (!on[(h - 1) * w + x]) border += label[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            if (!on[y * w]) border += label[y * w]
            if (!on[y * w + w - 1]) border += label[y * w + w - 1]
        }
        return Mask(w, h, BooleanArray(on.size) { on[it] || (label[it] !in border && sizes[label[it]] < maxArea) })
    }

    /** The Euclidean distance from each ink pixel to the nearest paper pixel (0 on paper). */
    fun distanceToPaper(): FloatArray {
        val inf = 1e12
        val f = DoubleArray(max(w, h))
        val d = DoubleArray(max(w, h))
        val g = DoubleArray(on.size) { if (on[it]) inf else 0.0 }
        // Felzenszwalb–Huttenlocher squared distance, columns then rows.
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = g[y * w + x]
            edt1(f, h, d)
            for (y in 0 until h) g[y * w + x] = d[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = g[y * w + x]
            edt1(f, w, d)
            for (x in 0 until w) g[y * w + x] = d[x]
        }
        return FloatArray(on.size) { sqrt(g[it]).toFloat() }
    }

    /** One row of the squared distance transform: the lower envelope of the parabolas at [f]. */
    private fun edt1(f: DoubleArray, n: Int, d: DoubleArray) {
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        var k = 0
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        fun cross(q: Int, p: Int) = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)
        for (q in 1 until n) {
            var s = cross(q, v[k])
            while (s <= z[k]) {
                k--
                s = cross(q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            d[q] = dq * dq + f[v[k]]
        }
    }
}
