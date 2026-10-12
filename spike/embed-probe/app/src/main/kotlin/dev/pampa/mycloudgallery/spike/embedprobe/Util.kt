package dev.pampa.mycloudgallery.spike.embedprobe

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

fun nowMs(): Double = System.nanoTime() / 1_000_000.0

object Fmt {
    fun mib(bytes: Long) = String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
    fun mb(v: Double) = String.format(Locale.US, "%.0f MB", v)
    fun ms(v: Double) = if (v.isNaN()) "n/d" else String.format(Locale.US, "%.1f", v)
    fun d(v: Double, dec: Int = 4) = if (v.isNaN()) "n/d" else String.format(Locale.US, "%.${dec}f", v)
    fun round(v: Double, dec: Int): Double {
        var f = 1.0
        repeat(dec) { f *= 10.0 }
        return Math.round(v * f) / f
    }
}

// ---- JSON: org.json rifiuta NaN/Infinity, quindi li trasformiamo in null ----

fun JSONObject.num(key: String, v: Double?, dec: Int = 2): JSONObject {
    if (v == null || v.isNaN() || v.isInfinite()) put(key, JSONObject.NULL) else put(key, Fmt.round(v, dec))
    return this
}

fun List<Double>.toJsonArray(dec: Int = 1): JSONArray {
    val a = JSONArray()
    for (v in this) a.put(if (v.isNaN() || v.isInfinite()) JSONObject.NULL else Fmt.round(v, dec))
    return a
}

fun List<Int>.toJsonIntArray(): JSONArray {
    val a = JSONArray()
    for (v in this) a.put(v)
    return a
}

// ---- Statistiche ----

data class Summary(val n: Int, val mean: Double, val p50: Double, val p95: Double, val min: Double, val max: Double) {
    fun toJson(dec: Int = 2): JSONObject = JSONObject()
        .put("n", n).num("mean", mean, dec).num("p50", p50, dec).num("p95", p95, dec)
        .num("min", min, dec).num("max", max, dec)

    fun short(unit: String = "ms") = "p50 ${Fmt.ms(p50)} / p95 ${Fmt.ms(p95)} $unit (n=$n)"

    companion object {
        fun of(values: List<Double>): Summary {
            val v = values.filter { !it.isNaN() }.sorted()
            if (v.isEmpty()) return Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            return Summary(v.size, v.average(), pct(v, 0.50), pct(v, 0.95), v.first(), v.last())
        }

        // Interpolazione lineare fra ranghi adiacenti.
        private fun pct(sorted: List<Double>, p: Double): Double {
            if (sorted.size == 1) return sorted[0]
            val pos = p * (sorted.size - 1)
            val lo = pos.toInt()
            val hi = min(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}

/** Immagini/s dato un elenco di tempi (ms) per immagine. */
fun imgPerSec(msPerImage: List<Double>): Double =
    if (msPerImage.isEmpty() || msPerImage.sum() <= 0.0) Double.NaN else 1000.0 * msPerImage.size / msPerImage.sum()

// ---- Vettori ----

object VecMath {
    fun dot(a: FloatArray, b: FloatArray, n: Int = min(a.size, b.size)): Double {
        var s = 0.0
        for (i in 0 until n) s += a[i].toDouble() * b[i].toDouble()
        return s
    }

    fun norm(a: FloatArray, n: Int = a.size): Double = sqrt(dot(a, a, n))

    fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        val na = norm(a, n)
        val nb = norm(b, n)
        return if (na == 0.0 || nb == 0.0) Double.NaN else dot(a, b, n) / (na * nb)
    }

    fun hasBadValues(v: FloatArray): Boolean {
        for (x in v) if (x.isNaN() || x.isInfinite()) return true
        return false
    }

    /** Tiene i primi dim valori e rinormalizza (come fa il motore con outputSize). */
    fun truncateNormalize(v: FloatArray, dim: Int): FloatArray {
        val d = min(dim, v.size)
        val out = v.copyOf(d)
        val n = norm(out)
        if (n > 0.0) for (i in 0 until d) out[i] = (out[i] / n).toFloat()
        return out
    }

    fun maxAbs(v: FloatArray): Float {
        var m = 0f
        for (x in v) if (abs(x) > m) m = abs(x)
        return m
    }
}
