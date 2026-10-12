package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class BackendKind(val label: String) { CPU("CPU"), GPU("GPU"), NPU("NPU") }

/** AUTO = predefinito della libreria (FP16 su GPU, FP32 su CPU, come da Config.kt). */
enum class ActKind(val label: String) { AUTO("auto"), FLOAT32("FP32"), FLOAT16("FP16") }

data class EngineSpec(
    val model: ModelId,
    val backend: BackendKind,
    val withVision: Boolean,
    val visionTokens: Int,
    val maxInputLength: Int,
    val activation: ActKind,
) {
    val key: String
        get() = "${model.name}-${backend.name}-${if (withVision) "v$visionTokens" else "txt"}-l$maxInputLength-${activation.name}"

    fun describe() = "${model.label} / ${backend.label} / " +
        (if (withVision) "visione $visionTokens tok" else "solo testo") + " / maxInput $maxInputLength / attiv. ${activation.label}"
}

data class InitInfo(
    val spec: EngineSpec,
    val initMs: Double,
    val cacheWasPopulated: Boolean,
    val cacheBytesBefore: Long,
    val cacheBytesAfter: Long,
    val memBefore: MemSnapshot,
    val memAfter: MemSnapshot,
    /** Solo NPU: quali librerie QNN/LiteRT sono state estratte in nativeLibraryDir. */
    val npuDiag: JSONObject? = null,
    /** Righe di logcat del nostro processo che citano acceleratori/backend (dopo l'init). */
    val logcat: List<String> = emptyList(),
)

data class CloseInfo(val closeMs: Double, val pssBeforeMb: Double, val pssAfterMb: Double)

/** cpuMs = tempo CPU del processo (tutti i thread) speso durante la chiamata: indica quanti core occupa il backend. */
data class Timed<T>(val value: T, val ms: Double, val cpuMs: Double = 0.0)

/** Inizializzazione fallita: nessun ripiego su altri backend, l'errore reale arriva fino alla UI. */
class EngineInitException(
    val spec: EngineSpec,
    val npuDiag: JSONObject?,
    val logcat: List<String>,
    cause: Throwable,
) : RuntimeException("Inizializzazione ${spec.backend.label} fallita: ${cause::class.java.simpleName}: ${cause.message}", cause)

/**
 * Un solo motore alla volta, tutte le chiamate (init, inferenza, close) su un unico thread dedicato:
 * il motore serializza comunque le richieste e la GPU preferisce restare sul thread che l'ha creata.
 */
class EngineManager(
    private val ctx: Context,
    private val store: ModelStore,
    private val probe: DeviceProbe,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "embed-engine") }.asCoroutineDispatcher()
    private var engine: EmbeddingEngine? = null
    private var spec: EngineSpec? = null

    @Volatile
    var loadedKey: String? = null
        private set

    private val cacheRoot get() = File(ctx.cacheDir, "engine")

    /** Carica il motore richiesto (chiudendo l'eventuale altro). Restituisce null se era gia quello caricato. */
    suspend fun ensure(wanted: EngineSpec): InitInfo? = withContext(dispatcher) {
        if (engine != null && spec == wanted) return@withContext null
        closeInternal()

        val modelFile = store.file(wanted.model)
        check(store.isReady(wanted.model)) { "${wanted.model.label}: modello non scaricato/verificato" }

        val dir = File(cacheRoot, wanted.key)
        val before = dirBytes(dir)
        val populated = before > 0
        dir.mkdirs()

        val backend: Backend = when (wanted.backend) {
            BackendKind.CPU -> Backend.CPU()
            BackendKind.GPU -> Backend.GPU()
            // Le librerie QNN e il dispatch Qualcomm sono nell'APK (useLegacyPackaging): nativeLibraryDir le contiene.
            BackendKind.NPU -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)
        }
        val diag = if (wanted.backend == BackendKind.NPU) npuDiagnostics() else null
        val activation: ActivationDataType? = when (wanted.activation) {
            ActKind.AUTO -> null
            ActKind.FLOAT32 -> ActivationDataType.FLOAT32
            ActKind.FLOAT16 -> ActivationDataType.FLOAT16
        }
        val config = EmbeddingEngineConfig(
            modelPath = modelFile.path,
            backend = backend,
            visionBackend = if (wanted.withVision) backend else null,
            audioBackend = null,
            cacheDir = dir.path,
            maxInputLength = wanted.maxInputLength,
            visionTokensPerImage = if (wanted.withVision) wanted.visionTokens else null,
            activationDataType = activation,
        )

        val memBefore = probe.memory()
        val eng = EmbeddingEngine(config)
        val t0 = nowMs()
        try {
            eng.initialize()
        } catch (t: Throwable) {
            try {
                eng.close()
            } catch (_: Throwable) {
            }
            throw EngineInitException(wanted, diag, Logcat.recent(), t)
        }
        val ms = nowMs() - t0
        engine = eng
        spec = wanted
        loadedKey = wanted.key
        InitInfo(wanted, ms, populated, before, dirBytes(dir), memBefore, probe.memory(), diag, Logcat.recent())
    }

    suspend fun embedImage(bytes: ByteArray): Timed<FloatArray> = withContext(dispatcher) {
        val e = engine ?: error("Motore non caricato")
        val c0 = android.os.Process.getElapsedCpuTime()
        val t0 = System.nanoTime()
        val r = e.computeEmbedding(listOf(InputData.Image(bytes)), EmbeddingOptions(normalize = true))
        val ms = (System.nanoTime() - t0) / 1e6
        Timed(r.embedding, ms, (android.os.Process.getElapsedCpuTime() - c0).toDouble())
    }

    /** outputSize != null chiede al motore il troncamento MRL (primi N valori + normalizzazione). */
    suspend fun embedText(text: String, outputSize: Int? = null): Timed<FloatArray> = withContext(dispatcher) {
        val e = engine ?: error("Motore non caricato")
        val c0 = android.os.Process.getElapsedCpuTime()
        val t0 = System.nanoTime()
        val r = e.computeEmbedding(
            listOf(InputData.Text(text)),
            EmbeddingOptions(normalize = true, outputSize = outputSize),
        )
        val ms = (System.nanoTime() - t0) / 1e6
        Timed(r.embedding, ms, (android.os.Process.getElapsedCpuTime() - c0).toDouble())
    }

    suspend fun close(): CloseInfo? = withContext(dispatcher) { closeInternal() }

    suspend fun clearCompileCache(): Long = withContext(dispatcher) {
        val n = dirBytes(cacheRoot)
        cacheRoot.deleteRecursively()
        n
    }

    private fun closeInternal(): CloseInfo? {
        val e = engine ?: return null
        val before = probe.memory().pssMb
        val t0 = nowMs()
        try {
            e.close()
        } finally {
            engine = null
            spec = null
            loadedKey = null
        }
        val ms = nowMs() - t0
        Thread.sleep(300) // lascia al sistema il tempo di restituire le pagine
        return CloseInfo(ms, before, probe.memory().pssMb)
    }

    private fun npuDiagnostics(): JSONObject {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("libQnn") || it.name.startsWith("libLiteRt") || it.name.startsWith("liblitertlm") }
            ?.sortedBy { it.name }
            ?: emptyList()
        return JSONObject()
            .put("socModel", Build.SOC_MODEL)
            .put("nativeLibraryDir", dir.path)
            .put("files", JSONArray(files.map { "${it.name} (${it.length() / 1024} KiB)" }))
    }

    private fun dirBytes(d: File): Long =
        if (!d.exists()) 0L else d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
