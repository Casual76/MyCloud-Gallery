package dev.pampa.mycloudgallery.spike.embedprobe

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class LastQuery(val raw: String, val formatted: String, val model: ModelId, val spec: EngineSpec, val vec: FloatArray)

class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val probe = DeviceProbe(ctx)
    val store = ModelStore(ctx)
    private val downloader = ModelDownloader(store)
    private val em = EngineManager(ctx, store, probe)
    private val results = ResultsLog(ctx, probe.deviceInfo())
    private val ranker = Ranker()

    // ---- stato osservato dalla UI ----
    var settings by mutableStateOf(Settings())
    var countText by mutableStateOf("200")
    var query by mutableStateOf("")
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var progressText by mutableStateOf("")
        private set
    var logLines by mutableStateOf<List<String>>(emptyList())
        private set
    var modelStates by mutableStateOf<Map<ModelId, ModelStatus>>(emptyMap())
        private set
    /** Un indice per backend (NPU / GPU / CPU), costruiti da esecuzioni separate di "Indicizza foto". */
    var indexes by mutableStateOf<Map<String, IndexData>>(emptyMap())
        private set
    var activeIndexKey by mutableStateOf<String?>(null)
        private set

    /** L'indice su cui lavorano ricerca e test: quello scelto, altrimenti il primo disponibile. */
    val index: IndexData? get() = activeIndexKey?.let { indexes[it] } ?: indexes.values.firstOrNull()
    var hits by mutableStateOf<List<Hit>>(emptyList())
        private set
    var searchInfo by mutableStateOf("")
        private set
    var resultsPath by mutableStateOf(results.file.path)
        private set
    var eventCount by mutableStateOf(0)
        private set

    private var job: Job? = null

    @Volatile
    private var stopFlag = false
    private var last: LastQuery? = null
    private val logLock = Any()

    init {
        refreshModels()
        log("Embed Probe pronto. Risultati: ${results.file.path}")
        val soc = "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}"
        log("Dispositivo: ${android.os.Build.MODEL}, SoC $soc, Android ${android.os.Build.VERSION.RELEASE}")
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = LinkedHashMap<String, IndexData>()
            // Migrazione del vecchio file unico index.bin (versione precedente dell'app).
            val legacy = File(ctx.filesDir, IndexWorkflow.LEGACY_INDEX_FILE)
            IndexStore.load(legacy)?.let {
                loaded[it.meta.backend] = it
                IndexStore.save(IndexWorkflow.indexFile(ctx, it.meta.backend), it)
                legacy.delete()
            }
            for (b in BackendKind.entries) IndexStore.load(IndexWorkflow.indexFile(ctx, b.name))?.let { loaded[b.name] = it }
            if (loaded.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    indexes = loaded
                    activeIndexKey = loaded.keys.firstOrNull { it == BackendKind.NPU.name } ?: loaded.keys.first()
                }
                loaded.forEach { (k, it) ->
                    log("Indice salvato ricaricato [$k]: ${it.size} vettori (${it.meta.modelKey}, ${it.meta.visionTokens} tok, ${it.meta.resolution} px)")
                }
            }
        }
    }

    // ------------------------------------------------------------------ infrastruttura

    fun log(msg: String) {
        Log.i("EmbedProbe", msg)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        synchronized(logLock) {
            val l = logLines + line
            logLines = if (l.size > 800) l.takeLast(800) else l
        }
    }

    fun refreshModels() {
        modelStates = ModelId.entries.associateWith { store.status(it) }
    }

    fun resultsJson(): String = results.toJsonString()

    private fun env() = JobEnv(
        app = ctx, em = em, store = store, probe = probe, results = results,
        log = ::log,
        setProgress = { p, t ->
            progress = p
            progressText = t
        },
        shouldStop = { stopFlag },
    )

    private fun launchJob(name: String, block: suspend () -> Unit) {
        if (busy != null) {
            log("Occupato: $busy")
            return
        }
        busy = name
        stopFlag = false
        progress = null
        progressText = ""
        job = viewModelScope.launch(Dispatchers.Default) {
            val t0 = nowMs()
            try {
                block()
            } catch (e: CancellationException) {
                log("$name: annullato")
                throw e
            } catch (t: Throwable) {
                val msg = "${t::class.java.simpleName}: ${t.message}"
                log("$name: ERRORE $msg")
                results.add("error", JSONObject().put("job", name).put("error", msg))
            } finally {
                withContext(NonCancellable) {
                    log("$name: terminato in ${Fmt.ms((nowMs() - t0) / 1000.0)} s")
                    busy = null
                    progress = null
                    progressText = ""
                    refreshModels()
                    eventCount = results.eventCount()
                }
            }
        }
    }

    /** Ferma il lavoro in corso: l'indicizzazione e i test si fermano tenendo cio che hanno gia calcolato, il download si interrompe (riprendibile). */
    fun stop() {
        stopFlag = true
        if (busy?.startsWith("Download") == true) job?.cancel()
    }

    // ------------------------------------------------------------------ 1. modelli

    fun downloadModels() = launchJob("Download modelli") { downloadList(ModelId.generic) }

    /** Scarica la variante NPU del 440M per il SoC rilevato (Build.SOC_MODEL). */
    fun downloadNpuModel() = launchJob("Download modello NPU") {
        val m = ModelId.deviceNpu()
        if (m == null) {
            log("NPU non supportata: SoC ${android.os.Build.SOC_MODEL} senza file precompilato (supportati: SM8750, SM8550)")
            return@launchJob
        }
        downloadList(listOf(m))
    }

    private suspend fun downloadList(models: List<ModelId>) {
        for (m in models) {
            if (store.isReady(m)) {
                log("${m.label}: gia presente e verificato (SHA-256 ok)")
                continue
            }
            val resumedFrom = store.status(m).bytesOnDisk
            log("${m.label}: download da Hugging Face (${Fmt.mib(m.sizeBytes)})" + if (resumedFrom > 0) ", riprendo da ${Fmt.mib(resumedFrom)}" else "")
            val t0 = nowMs()
            try {
                downloader.download(m) { p ->
                    progress = if (p.totalBytes > 0) p.doneBytes.toFloat() / p.totalBytes else null
                    progressText = "${m.label}: ${p.phase} ${Fmt.mib(p.doneBytes)} / ${Fmt.mib(p.totalBytes)}" +
                        if (p.bytesPerSec > 0) " (${Fmt.d(p.bytesPerSec / 1048576.0, 1)} MiB/s)" else ""
                    modelStates = ModelId.entries.associateWith { id ->
                        if (id == m) ModelStatus(ModelState.PARTIAL, p.doneBytes) else store.status(id)
                    }
                }
            } catch (e: Sha256MismatchException) {
                log("${m.label}: ERRORE SHA-256. Atteso ${e.expected}, ottenuto ${e.actual}. File cancellato.")
                results.add("download_error", JSONObject().put("model", m.name).put("error", "sha256_mismatch").put("actual", e.actual))
                throw e
            }
            val secs = (nowMs() - t0) / 1000.0
            log("${m.label}: scaricato e verificato (SHA-256 ok) in ${Fmt.ms(secs)} s")
            results.add(
                "download",
                JSONObject().put("model", m.name).put("file", m.fileName).put("soc", m.soc ?: JSONObject.NULL)
                    .put("bytes", m.sizeBytes).num("seconds", secs, 1)
                    .put("resumedFromBytes", resumedFrom).put("sha256Verified", true),
            )
        }
    }

    fun showModelInfo() = launchJob("ModelInfo") {
        val arr = JSONArray()
        log("SoC del dispositivo (Build.SOC_MODEL): ${android.os.Build.SOC_MODEL} - variante NPU attesa: ${ModelId.deviceNpu()?.fileName ?: "nessuna (SoC non supportato)"}")
        for (m in ModelId.relevant()) {
            if (!store.isReady(m)) {
                log("${m.label}: non scaricato")
                continue
            }
            val j = JSONObject().put("model", m.name).put("file", m.fileName).put("deviceSoc", android.os.Build.SOC_MODEL)
            log("--- ModelInfo ${m.label}")
            val t0 = nowMs()
            try {
                ModelInfo.from(store.file(m).path).use { info ->
                    val ms = nowMs() - t0
                    log("  ModelInfo.from(): ${Fmt.ms(ms)} ms")
                    j.num("fromMs", ms, 1)
                    fun field(name: String, f: () -> Any?) {
                        try {
                            val v = f()
                            val txt = when (v) {
                                is IntArray -> v.joinToString(",", "[", "]")
                                is List<*> -> v.joinToString(",", "[", "]")
                                else -> v.toString()
                            }
                            log("  $name = $txt")
                            j.put(name, if (v is IntArray) JSONArray(v.toList()) else if (v is List<*>) JSONArray(v.map { it.toString() }) else (v?.toString() ?: JSONObject.NULL))
                        } catch (t: Throwable) {
                            log("  $name = (errore ${t::class.java.simpleName}: ${t.message})")
                            j.put(name, "error: ${t.message}")
                        }
                    }
                    field("modelType") { info.modelType }
                    if (info is ModelInfo.Embedding) {
                        field("dimension") { info.dimension() }
                        field("signatureSelection") { info.signatureSelection() }
                        field("isDynamicContext") { info.isDynamicContext() }
                        field("visionSignatureSelection") { info.visionSignatureSelection() }
                    }
                    field("maxContextTokens") { info.maxContextTokens() }
                    field("maxVisionTokenBudget") { info.maxVisionTokenBudget() }
                    field("inputModalities") { info.inputModalities() }
                    field("minRuntimeVersion") { info.minRuntimeVersion() }
                    // Per ogni modalita: backend supportati, marca NPU e SoC per cui il sotto-modello e compilato.
                    for (mod in Modality.entries) {
                        field("supportedBackends(${mod.name})") { info.supportedBackends(mod) }
                        field("npuBrand(${mod.name})") { info.npuBrand(mod) }
                        field("socName(${mod.name})") { info.socName(mod) }
                    }
                    if (m.isNpu) {
                        val soc = try { info.socName(Modality.TEXT) } catch (_: Throwable) { null }
                        val match = soc != null && soc.equals(android.os.Build.SOC_MODEL, ignoreCase = true)
                        log("  controllo: socName(TEXT)=$soc, dispositivo=${android.os.Build.SOC_MODEL} -> ${if (match) "corrispondono" else "NON corrispondono"}")
                        j.put("socMatchesDevice", match)
                    }
                }
            } catch (t: Throwable) {
                log("  ERRORE ModelInfo: ${t::class.java.simpleName}: ${t.message}")
                j.put("error", "${t::class.java.simpleName}: ${t.message}")
            }
            arr.put(j)
        }
        results.add("model_info", JSONObject().put("models", arr))
    }

    // ------------------------------------------------------------------ 2. indicizzazione

    fun startIndexing() = launchJob("Indicizzazione") {
        val count = (countText.toIntOrNull() ?: 200).coerceIn(1, 20000)
        val data = IndexWorkflow.run(env(), settings, count)
        if (data != null) {
            val key = data.meta.backend
            // Allineamento con gli altri indici: servono le stesse foto nello stesso ordine per il confronto incrociato.
            indexes.filterKeys { it != key }.forEach { (k, other) ->
                val same = other.ids.contentEquals(data.ids)
                val common = other.ids.toSet().intersect(data.ids.toSet()).size
                log("Allineamento indice $key (${data.size}) con $k (${other.size}): " +
                    if (same) "stessa lista di foto nello stesso ordine" else "liste diverse ($common foto in comune): il confronto incrociato usera solo le comuni")
            }
            withContext(Dispatchers.Main) {
                indexes = indexes + (key to data)
                activeIndexKey = key
                hits = emptyList()
                searchInfo = ""
            }
            ranker.invalidate()
            last = null
        }
    }

    /** Sceglie quale indice usano ricerca e test (gli altri restano in memoria e su file). */
    fun selectIndex(key: String) {
        if (key !in indexes) return
        activeIndexKey = key
        hits = emptyList()
        searchInfo = ""
        last = null
    }

    fun runCrossCompare() = launchJob("Confronto incrociato") { CrossCompare.run(env(), settings, indexes) }

    fun releaseEngine() = launchJob("Libera motore") {
        val c = em.close()
        if (c == null) {
            log("Nessun motore caricato")
        } else {
            log("close(): ${Fmt.ms(c.closeMs)} ms, PSS ${Fmt.mb(c.pssBeforeMb)} -> ${Fmt.mb(c.pssAfterMb)}")
            results.add("engine_close", c.toJson())
        }
    }

    fun clearCompileCache() = launchJob("Svuota cache") {
        val c = em.close()
        if (c != null) log("Motore chiuso prima di svuotare la cache (PSS ${Fmt.mb(c.pssBeforeMb)} -> ${Fmt.mb(c.pssAfterMb)})")
        val n = em.clearCompileCache()
        log("Cache di compilazione svuotata (${Fmt.mib(n)}): il prossimo initialize() sara a freddo")
        results.add("cache_cleared", JSONObject().put("bytes", n))
    }

    // ------------------------------------------------------------------ 3. coerenza

    fun runCpuVsGpu() = launchJob("CPU vs GPU") { TestWorkflows.cpuVsGpu(env(), settings, index) }

    fun runNpuVsCpu() = launchJob("NPU vs CPU") { TestWorkflows.npuVsCpu(env(), settings, index) }

    fun runModelCompare() = launchJob("270M vs 440M") { TestWorkflows.modelCompare(env(), settings, index) }

    // ------------------------------------------------------------------ 4. ricerca

    fun search() = launchJob("Ricerca") {
        val idx = index
        if (idx == null) {
            log("Nessun indice: indicizza prima delle foto")
            return@launchJob
        }
        val q = query.trim()
        if (q.isEmpty()) {
            log("Scrivi una query")
            return@launchJob
        }
        val s = settings
        val spec = s.querySpec() // lancia con messaggio chiaro se la NPU non e disponibile: nessun ripiego
        if (!store.isReady(spec.model)) {
            log("Modello ${spec.model.label} non scaricato")
            return@launchJob
        }
        val t0 = nowMs()
        val init = env().ensureEngine(spec)
        val t1 = nowMs()
        val formatted = s.prefix.apply(q)
        val emb = em.embedText(formatted)
        val t2 = nowMs()
        last = LastQuery(q, formatted, s.queryModel, spec, emb.value)
        val (top, base) = synchronized(ranker) {
            ranker.topK(idx, emb.value, s.dim, s.precision, 20) to ranker.topK(idx, emb.value, 768, Precision.F32, 20)
        }
        val t3 = nowMs()
        val total = t3 - t0
        // I vettori NPU non coincidono con quelli CPU/GPU (coseno misurato 0,78-0,89 sull'S25): indice e query vanno fatti sullo stesso backend.
        val mismatch = (idx.meta.backend == BackendKind.NPU.name) != (spec.backend == BackendKind.NPU)
        val warn = if (mismatch) " ATTENZIONE: indice costruito con ${idx.meta.backend}, query calcolata con ${spec.backend.name}: i vettori NPU non sono compatibili con CPU/GPU, i punteggi non sono affidabili." else ""
        if (mismatch) log("Ricerca:$warn")
        val info = "\"$q\" [${s.prefix.label}, query ${s.queryModel.name}, ${s.dim}d ${s.precision.label}] - totale ${Fmt.ms(total)} ms " +
            "(caricamento ${Fmt.ms(t1 - t0)}, embedding ${Fmt.ms(emb.ms)}, punteggi ${Fmt.ms(t3 - t2)})" + warn
        withContext(Dispatchers.Main) {
            hits = top
            searchInfo = info + overlapText(top, base)
        }
        log("Ricerca: $info" + overlapText(top, base))
        results.add(
            "search",
            searchJson(q, formatted, s, spec, top, base)
                .put("indexQueryBackendMismatchNpu", mismatch)
                .num("totalMs", total, 1).num("engineLoadMs", t1 - t0, 1).num("embedMs", emb.ms, 1).num("scoreMs", t3 - t2, 2)
                .put("engineInit", init?.toJson() ?: JSONObject.NULL),
        )
        eventCount = results.eventCount()
    }

    /** Ricalcola la classifica con altra dimensione/precisione senza ripetere l'embedding della query. */
    fun rerank() {
        val l = last ?: return
        val idx = index ?: return
        val s = settings
        viewModelScope.launch(Dispatchers.Default) {
            val t0 = nowMs()
            val (top, base) = synchronized(ranker) {
                ranker.topK(idx, l.vec, s.dim, s.precision, 20) to ranker.topK(idx, l.vec, 768, Precision.F32, 20)
            }
            val ms = nowMs() - t0
            val info = "\"${l.raw}\" [${s.prefix.label}, query ${l.model.name}, ${s.dim}d ${s.precision.label}] - classifica ricalcolata in ${Fmt.ms(ms)} ms (query non ripetuta)" +
                overlapText(top, base)
            withContext(Dispatchers.Main) {
                hits = top
                searchInfo = info
            }
            log("Ricerca: $info")
            results.add("search_rerank", searchJson(l.raw, l.formatted, s, l.spec, top, base).num("scoreMs", ms, 2))
            eventCount = results.eventCount()
        }
    }

    private fun overlapText(top: List<Hit>, base: List<Hit>) =
        " | sovrapposizione con base 768d float32: top-10 ${overlap(base, top, 10)}/10, top-20 ${overlap(base, top, 20)}/20"

    private fun searchJson(raw: String, formatted: String, s: Settings, spec: EngineSpec, top: List<Hit>, base: List<Hit>): JSONObject =
        JSONObject()
            .put("backend", spec.backend.name)
            .put("query", raw).put("formattedQuery", formatted)
            .put("prefixFormat", s.prefix.name).put("queryModel", s.queryModel.name)
            .put("querySpec", spec.toJson())
            .put("dim", s.dim).put("precision", s.precision.name)
            .put("indexSize", index?.size ?: 0)
            .put("indexMeta", index?.meta?.let { JSONObject().put("model", it.modelKey).put("backend", it.backend).put("visionTokens", it.visionTokens).put("resolution", it.resolution) } ?: JSONObject.NULL)
            .put("topPositions", top.map { it.index }.toJsonIntArray())
            .put("topScores", top.map { it.score }.toJsonArray(4))
            .put("basePositions", base.map { it.index }.toJsonIntArray())
            .put("overlapTop10", overlap(base, top, 10)).put("overlapTop20", overlap(base, top, 20))

    override fun onCleared() {
        stopFlag = true
        super.onCleared()
    }
}
