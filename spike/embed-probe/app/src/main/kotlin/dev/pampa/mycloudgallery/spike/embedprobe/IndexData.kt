package dev.pampa.mycloudgallery.spike.embedprobe

import android.util.Half
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class Precision(val label: String) { F32("float32"), F16("float16"), INT8("int8") }

enum class PrefixFormat(val label: String) {
    RAW("grezza"),
    HF("formato HF"),
    LITERT("formato LiteRT-LM");

    /** Pagina 7 della ricerca: il motore NON aggiunge prefissi, li mettiamo noi. */
    fun apply(q: String): String = when (this) {
        RAW -> q
        HF -> "task: search result | query: $q"
        LITERT -> "task: search query | text: $q"
    }

    val example: String
        get() = when (this) {
            RAW -> "testo cosi com'e"
            HF -> "task: search result | query: {q}"
            LITERT -> "task: search query | text: {q}"
        }
}

class IndexMeta(
    val modelKey: String,
    val backend: String,
    val visionTokens: Int,
    val resolution: Int,
    val convert: String,
    val dim: Int,
    val createdAtMs: Long,
)

/** Vettori 768 float32 gia normalizzati, con gli id MediaStore (solo in memoria e nel file privato dell'app). */
class IndexData(val ids: LongArray, val vecs: Array<FloatArray>, val meta: IndexMeta) {
    val size: Int get() = ids.size
}

object IndexStore {
    private const val MAGIC = 0x45504958 // "EPIX"
    private const val VERSION = 1

    fun save(file: File, d: IndexData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(d.meta.modelKey)
            out.writeUTF(d.meta.backend)
            out.writeInt(d.meta.visionTokens)
            out.writeInt(d.meta.resolution)
            out.writeUTF(d.meta.convert)
            out.writeInt(d.meta.dim)
            out.writeLong(d.meta.createdAtMs)
            out.writeInt(d.size)
            for (id in d.ids) out.writeLong(id)
            val bb = ByteBuffer.allocate(d.meta.dim * 4)
            for (v in d.vecs) {
                bb.clear()
                bb.asFloatBuffer().put(v, 0, d.meta.dim)
                out.write(bb.array(), 0, d.meta.dim * 4)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(file: File): IndexData? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
                val modelKey = inp.readUTF()
                val backend = inp.readUTF()
                val tokens = inp.readInt()
                val res = inp.readInt()
                val convert = inp.readUTF()
                val dim = inp.readInt()
                val created = inp.readLong()
                val n = inp.readInt()
                val ids = LongArray(n) { inp.readLong() }
                val raw = ByteArray(dim * 4)
                val vecs = Array(n) {
                    inp.readFully(raw)
                    FloatArray(dim).also { f -> ByteBuffer.wrap(raw).asFloatBuffer().get(f) }
                }
                IndexData(ids, vecs, IndexMeta(modelKey, backend, tokens, res, convert, dim, created))
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class Hit(val index: Int, val id: Long, val score: Double)

/**
 * Classifica per coseno sui vettori indicizzati dopo troncamento MRL (+ rinormalizzazione) e
 * simulazione della precisione di memorizzazione (float16 / int8 con scala per vettore).
 */
class Ranker {
    private var cacheKey: String? = null
    private var mat: Array<FloatArray> = emptyArray()
    private var norms: DoubleArray = DoubleArray(0)

    private fun prepare(index: IndexData, dim: Int, prec: Precision) {
        val key = "${System.identityHashCode(index)}|$dim|${prec.name}"
        if (key == cacheKey) return
        mat = Array(index.size) { i ->
            val t = VecMath.truncateNormalize(index.vecs[i], dim)
            when (prec) {
                Precision.F32 -> t
                Precision.F16 -> FloatArray(t.size) { Half.toFloat(Half.toHalf(t[it])) }
                Precision.INT8 -> {
                    val m = VecMath.maxAbs(t)
                    if (m == 0f) t else {
                        val scale = m / 127f
                        FloatArray(t.size) { (t[it] / scale).roundToInt().coerceIn(-127, 127) * scale }
                    }
                }
            }
        }
        norms = DoubleArray(index.size) { VecMath.norm(mat[it]) }
        cacheKey = key
    }

    fun topK(index: IndexData, query768: FloatArray, dim: Int, prec: Precision, k: Int): List<Hit> {
        val d = min(dim, index.meta.dim)
        prepare(index, d, prec)
        val q = VecMath.truncateNormalize(query768, d)
        val qn = VecMath.norm(q)
        val scores = DoubleArray(index.size) { i ->
            val n = norms[i]
            if (n == 0.0 || qn == 0.0) Double.NEGATIVE_INFINITY else VecMath.dot(q, mat[i]) / (qn * n)
        }
        return scores.indices.sortedByDescending { scores[it] }.take(k)
            .map { Hit(it, index.ids[it], scores[it]) }
    }

    fun invalidate() {
        cacheKey = null
    }
}

fun overlap(a: List<Hit>, b: List<Hit>, k: Int): Int {
    val sa = a.take(k).map { it.index }.toSet()
    return b.take(k).count { it.index in sa }
}

fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
    var m = 0f
    for (i in 0 until min(a.size, b.size)) m = maxOf(m, abs(a[i] - b[i]))
    return m
}
