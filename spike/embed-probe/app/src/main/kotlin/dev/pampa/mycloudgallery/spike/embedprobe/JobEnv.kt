package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val backend: BackendKind = BackendKind.GPU,
    val tokens: Int = 70,
    val res: Int = 768,
    val convert: ConvertFmt = ConvertFmt.JPEG,
    /** 0 = automatico (128 con 70 token visivi, 256 con 140). */
    val maxLen: Int = 0,
    val activation: ActKind = ActKind.AUTO,
    val pipelined: Boolean = false,
    val queryModel: ModelId = ModelId.M440,
    val prefix: PrefixFormat = PrefixFormat.RAW,
    val dim: Int = 768,
    val precision: Precision = Precision.F32,
) {
    fun resolvedMaxLen(): Int = if (maxLen > 0) maxLen else if (tokens >= 140) 256 else 128

    /** Il file del 440M da usare con un backend: sulla NPU serve la variante precompilata per il SoC. */
    fun modelFor(backend: BackendKind): ModelId =
        if (backend != BackendKind.NPU) ModelId.M440
        else ModelId.deviceNpu() ?: throw IllegalStateException(
            "NPU non disponibile: SoC ${Build.SOC_MODEL} senza file precompilato (supportati: SM8750, SM8550)",
        )

    /** Motore per l'indicizzazione e per le query del 440M (stesso motore: riusabile senza ricaricare). */
    fun visionSpec(backend: BackendKind = this.backend) =
        EngineSpec(modelFor(backend), backend, true, tokens, resolvedMaxLen(), activation)

    /** Motore per le query: 440M con visione (come l'indice) oppure 270M solo testo. Nessun ripiego silenzioso. */
    fun querySpec(): EngineSpec =
        if (queryModel == ModelId.M440) visionSpec()
        else if (backend == BackendKind.NPU) throw IllegalStateException(
            "Il 270M non ha una variante NPU in questo spike: per la query scegli il modello 440M oppure il backend CPU/GPU",
        )
        else EngineSpec(ModelId.M270, backend, false, 0, if (maxLen > 0) maxLen else 128, activation)
}

/** "Classe: messaggio <- Classe: messaggio ..." lungo tutta la catena di cause. */
fun causeChain(t: Throwable): String =
    generateSequence(t) { it.cause }.take(6).joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message}" }

/** Tutto cio che i flussi di lavoro (indicizzazione, test, ricerca) usano per parlare con l'esterno. */
class JobEnv(
    val app: Context,
    val em: EngineManager,
    val store: ModelStore,
    val probe: DeviceProbe,
    val results: ResultsLog,
    val log: (String) -> Unit,
    val setProgress: (Float?, String) -> Unit,
    val shouldStop: () -> Boolean,
) {
    /**
     * Carica il motore richiesto. Se l'inizializzazione fallisce (tipico della NPU) scrive il messaggio reale,
     * le librerie presenti, le righe di logcat utili e un evento nel JSON, poi rilancia: nessun ripiego.
     */
    suspend fun ensureEngine(spec: EngineSpec): InitInfo? = try {
        em.ensure(spec)
    } catch (e: EngineInitException) {
        log("ERRORE inizializzazione ${spec.backend.label} (${spec.describe()})")
        log("  ${causeChain(e.cause ?: e)}")
        e.npuDiag?.let { d ->
            log("  SoC ${d.optString("socModel")}, nativeLibraryDir ${d.optString("nativeLibraryDir")}")
            log("  librerie QNN/LiteRT estratte: ${d.optJSONArray("files")?.toString() ?: "[]"}")
        }
        e.logcat.takeLast(15).forEach { log("  logcat: $it") }
        results.add(
            "engine_init_failed",
            JSONObject().put("backend", spec.backend.name).put("spec", spec.toJson())
                .put("error", causeChain(e.cause ?: e))
                .put("npuDiag", e.npuDiag ?: JSONObject.NULL)
                .put("logcat", JSONArray(e.logcat)),
        )
        throw e
    }
}
