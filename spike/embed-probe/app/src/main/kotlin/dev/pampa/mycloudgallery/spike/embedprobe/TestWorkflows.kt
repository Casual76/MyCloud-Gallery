package dev.pampa.mycloudgallery.spike.embedprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

object TestWorkflows {
    private const val THRESHOLD = 0.99

    // ------------------------------------------------------------------ confronto fra backend (CPU vs GPU, NPU vs CPU)

    private class BackendRun(
        val backend: BackendKind,
        var initInfo: InitInfo? = null,
        var prevClose: CloseInfo? = null,
        var error: String? = null,
        val imgVecs: ArrayList<FloatArray?> = ArrayList(),
        val txtVecs: ArrayList<FloatArray?> = ArrayList(),
        val imgMs: ArrayList<Double> = ArrayList(),
        val txtMs: ArrayList<Double> = ArrayList(),
        val imgCpuMs: ArrayList<Double> = ArrayList(),
        val txtCpuMs: ArrayList<Double> = ArrayList(),
        var memAfter: MemSnapshot? = null,
        var peakPss: Double = 0.0,
        var modelFile: String = "",
    )

    suspend fun cpuVsGpu(env: JobEnv, s: Settings, index: IndexData?) =
        compareBackends(env, s, index, BackendKind.CPU, BackendKind.GPU, "cpu_vs_gpu")

    suspend fun npuVsCpu(env: JobEnv, s: Settings, index: IndexData?) =
        compareBackends(env, s, index, BackendKind.CPU, BackendKind.NPU, "npu_vs_cpu")

    /** Core CPU medi occupati durante le chiamate: tempo CPU del processo / tempo di orologio. */
    private fun cores(cpuMs: List<Double>, wallMs: List<Double>): Double =
        if (wallMs.isEmpty() || wallMs.sum() <= 0.0) Double.NaN else cpuMs.sum() / wallMs.sum()

    /**
     * Stesse 20 immagini e 20 testi su due backend (il primo e il riferimento), coseno medio/minimo, latenze,
     * CPU spesa e memoria. Ogni backend e una serie a parte nel JSON ("backends": {"CPU": ..., "NPU": ...}).
     */
    private suspend fun compareBackends(
        env: JobEnv,
        s: Settings,
        index: IndexData?,
        ref: BackendKind,
        other: BackendKind,
        eventType: String,
    ) {
        // Le specifiche (e quindi i file) prima di qualsiasi lavoro: se la NPU non e supportata l'errore e subito esplicito.
        val specs = listOf(ref, other).associateWith { s.visionSpec(it) }
        for ((b, spec) in specs) {
            if (!env.store.isReady(spec.model)) {
                env.log("Modello per ${b.label} (${spec.model.label}) non scaricato/verificato: scaricalo prima")
                return
            }
        }
        val ids = index?.ids?.take(20) ?: withContext(Dispatchers.IO) { Photos.query(env.app, 20) }
        if (ids.isEmpty()) {
            env.log("Nessuna foto disponibile (serve il permesso o almeno una foto in galleria)")
            return
        }
        val texts = ItalianTexts.all.take(20)

        env.setProgress(null, "Preparazione ${ids.size} immagini...")
        val prepared = ids.mapIndexed { i, id ->
            withContext(Dispatchers.Default) { Photos.prepare(env.app, i, id, s.res, s.convert) }
        }
        val failedPrep = prepared.count { it.bytes == null }
        val title = "${other.label} vs ${ref.label}"
        env.log("=== $title: ${ids.size} immagini (${s.res} px, ${s.convert.label}, ${s.tokens} token) e ${texts.size} testi. Preparazione fallita per $failedPrep")

        val runs = listOf(ref, other).map { BackendRun(it) }
        for (run in runs) {
            if (env.shouldStop()) break
            val spec = specs.getValue(run.backend)
            run.modelFile = spec.model.fileName
            env.log("--- ${run.backend.label} (${spec.model.fileName})")
            try {
                run.prevClose = env.em.close()
                env.setProgress(null, "${run.backend.label}: caricamento motore...")
                val init = env.ensureEngine(spec)
                run.initInfo = init
                if (init != null) {
                    env.log("  initialize() ${Fmt.ms(init.initMs)} ms (cache ${if (init.cacheWasPopulated) "presente" else "vuota"}), ${init.memAfter.short()}")
                    init.logcat.takeLast(6).forEach { env.log("  logcat: $it") }
                }
                var peak = env.probe.memory().pssMb
                val total = prepared.size + texts.size
                for ((i, p) in prepared.withIndex()) {
                    if (env.shouldStop()) break
                    val bytes = p.bytes
                    if (bytes == null) {
                        run.imgVecs.add(null)
                        continue
                    }
                    val t = env.em.embedImage(bytes)
                    run.imgVecs.add(t.value)
                    run.imgMs.add(t.ms)
                    run.imgCpuMs.add(t.cpuMs)
                    if (i % 5 == 4) peak = max(peak, env.probe.memory().pssMb)
                    env.setProgress((i + 1f) / total, "${run.backend.label}: immagine ${i + 1}/${prepared.size}")
                }
                for ((i, tx) in texts.withIndex()) {
                    if (env.shouldStop()) break
                    val t = env.em.embedText(tx)
                    run.txtVecs.add(t.value)
                    run.txtMs.add(t.ms)
                    run.txtCpuMs.add(t.cpuMs)
                    env.setProgress((prepared.size + i + 1f) / total, "${run.backend.label}: testo ${i + 1}/${texts.size}")
                }
                run.memAfter = env.probe.memory()
                run.peakPss = max(peak, run.memAfter!!.pssMb)
                env.log("  immagini ${Summary.of(run.imgMs).short()} - CPU ${Fmt.d(cores(run.imgCpuMs, run.imgMs), 2)} core medi")
                env.log("  testi    ${Summary.of(run.txtMs).short()} - CPU ${Fmt.d(cores(run.txtCpuMs, run.txtMs), 2)} core medi")
                env.log("  memoria  ${run.memAfter!!.short()} - picco PSS ${Fmt.mb(run.peakPss)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                run.error = causeChain(e)
                env.log("  ERRORE ${run.backend.label}: ${run.error}")
            }
        }

        val a = runs[0]
        val b = runs[1]
        fun cosines(x: List<FloatArray?>, y: List<FloatArray?>): List<Double> =
            x.indices.mapNotNull { i -> if (i < y.size && x[i] != null && y[i] != null) VecMath.cosine(x[i]!!, y[i]!!) else null }

        val imgCos = cosines(a.imgVecs, b.imgVecs)
        val txtCos = cosines(a.txtVecs, b.txtVecs)
        fun line(label: String, c: List<Double>): String {
            if (c.isEmpty()) return "  $label: nessun confronto possibile"
            val mn = c.minOrNull()!!
            val ok = mn >= THRESHOLD
            return "  $label: coseno medio ${Fmt.d(c.average(), 5)}, minimo ${Fmt.d(mn, 5)} su ${c.size} -> ${if (ok) "OK (>= 0,99)" else "SOTTO SOGLIA 0,99"}"
        }
        env.log("Coerenza $title (riferimento ${ref.label})")
        env.log(line("immagini", imgCos))
        env.log(line("testi   ", txtCos))
        fun badCount(v: List<FloatArray?>) = v.count { it != null && VecMath.hasBadValues(it) }
        fun normRange(v: List<FloatArray?>): Pair<Double, Double> {
            val n = v.filterNotNull().map { VecMath.norm(it) }
            return (n.minOrNull() ?: Double.NaN) to (n.maxOrNull() ?: Double.NaN)
        }
        for (r in runs) {
            val (lo, hi) = normRange(r.imgVecs + r.txtVecs)
            env.log("  ${r.backend.label}: vettori con NaN/inf ${badCount(r.imgVecs) + badCount(r.txtVecs)}, norme ${Fmt.d(lo, 5)}..${Fmt.d(hi, 5)}")
        }

        fun runJson(r: BackendRun): JSONObject {
            val (nMin, nMax) = normRange(r.imgVecs + r.txtVecs)
            return JSONObject()
                .put("backend", r.backend.name)
                .put("modelFile", r.modelFile)
                .put("error", r.error ?: JSONObject.NULL)
                .put("init", r.initInfo?.toJson() ?: JSONObject.NULL)
                .put("closeOfPreviousEngine", r.prevClose?.toJson() ?: JSONObject.NULL)
                .put("imageMs", Summary.of(r.imgMs).toJson())
                .put("textMs", Summary.of(r.txtMs).toJson())
                .num("imageCpuCores", cores(r.imgCpuMs, r.imgMs), 3)
                .num("textCpuCores", cores(r.txtCpuMs, r.txtMs), 3)
                .put("memoryAfter", r.memAfter?.toJson() ?: JSONObject.NULL)
                .num("peakPssMb", r.peakPss, 1)
                .put("badVectors", badCount(r.imgVecs) + badCount(r.txtVecs))
                .num("normMin", nMin, 5).num("normMax", nMax, 5)
        }

        fun cosJson(c: List<Double>) = JSONObject().put("n", c.size)
            .num("mean", if (c.isEmpty()) Double.NaN else c.average(), 5)
            .num("min", c.minOrNull() ?: Double.NaN, 5)
            .put("perItem", c.toJsonArray(5))

        env.results.add(
            eventType,
            JSONObject()
                .put("backend", other.name) // serie principale dell'evento; il riferimento e in "referenceBackend"
                .put("referenceBackend", ref.name)
                .put(
                    "config",
                    JSONObject().put("images", ids.size).put("texts", texts.size).put("resolution", s.res)
                        .put("convert", s.convert.name).put("visionTokens", s.tokens)
                        .put("maxInputLength", s.resolvedMaxLen()).put("activation", s.activation.name)
                        .put("prepareFailed", failedPrep).put("threshold", THRESHOLD),
                )
                .put("backends", JSONObject().put(a.backend.name, runJson(a)).put(b.backend.name, runJson(b)))
                .put("imageCosine", cosJson(imgCos)).put("textCosine", cosJson(txtCos))
                .put("imagesPassThreshold", imgCos.isNotEmpty() && imgCos.min() >= THRESHOLD)
                .put("textsPassThreshold", txtCos.isNotEmpty() && txtCos.min() >= THRESHOLD),
        )
    }

    // ------------------------------------------------------------------ 270M vs 440M

    private class TextRun(
        val label: String,
        val spec: EngineSpec,
    ) {
        var error: String? = null
        var initInfo: InitInfo? = null
        var prevClose: CloseInfo? = null
        val latMs = ArrayList<Double>()
        val vecs = ArrayList<FloatArray>()
        var memAfter: MemSnapshot? = null
        var peakPss = 0.0
        var mrl: JSONArray? = null

        val coldMs: Double get() = (initInfo?.initMs ?: Double.NaN) + (latMs.firstOrNull() ?: Double.NaN)
        val warm: Summary get() = Summary.of(latMs.drop(1))
        val ok: Boolean get() = error == null && vecs.isNotEmpty()
    }

    suspend fun modelCompare(env: JobEnv, s: Settings, index: IndexData?) {
        if (s.backend == BackendKind.NPU) {
            env.log("270M vs 440M gira su CPU o GPU: il 270M non ha una variante NPU in questo spike. Scegli CPU/GPU nelle impostazioni.")
            return
        }
        for (m in ModelId.generic) {
            if (!env.store.isReady(m)) {
                env.log("Modello ${m.label} non scaricato/verificato")
                return
            }
        }
        val texts = ItalianTexts.all
        val len = if (s.maxLen > 0) s.maxLen else 128
        env.log("=== 270M vs 440M: ${texts.size} testi italiani, backend ${s.backend.label}, maxInput testo-only $len")

        val r270 = TextRun("270M testo", EngineSpec(ModelId.M270, s.backend, false, 0, len, s.activation))
        val r440v = TextRun("440M con visione", s.visionSpec())
        val r440t = TextRun("440M solo testo (visionBackend = null)", EngineSpec(ModelId.M440, s.backend, false, 0, len, s.activation))
        val runs = listOf(r270, r440v, r440t)

        for ((ri, run) in runs.withIndex()) {
            if (env.shouldStop()) break
            env.log("--- ${run.label}: ${run.spec.describe()}")
            try {
                run.prevClose = env.em.close()
                run.prevClose?.let { env.log("  close() del motore precedente: ${Fmt.ms(it.closeMs)} ms, PSS ${Fmt.mb(it.pssBeforeMb)} -> ${Fmt.mb(it.pssAfterMb)}") }
                env.setProgress(null, "${run.label}: caricamento...")
                val init = env.ensureEngine(run.spec)
                run.initInfo = init
                if (init != null) env.log("  initialize() ${Fmt.ms(init.initMs)} ms (cache ${if (init.cacheWasPopulated) "presente" else "vuota"}); ${init.memAfter.short()}")
                var peak = env.probe.memory().pssMb
                for ((i, t) in texts.withIndex()) {
                    if (env.shouldStop()) break
                    val r = env.em.embedText(t)
                    run.vecs.add(r.value)
                    run.latMs.add(r.ms)
                    if (i % 10 == 9) peak = max(peak, env.probe.memory().pssMb)
                    env.setProgress((ri + (i + 1f) / texts.size) / runs.size, "${run.label}: testo ${i + 1}/${texts.size}")
                }
                run.memAfter = env.probe.memory()
                run.peakPss = max(peak, run.memAfter!!.pssMb)
                env.log("  query a freddo (init + prima) ${Fmt.ms(run.coldMs)} ms; prima query ${Fmt.ms(run.latMs.firstOrNull() ?: Double.NaN)} ms")
                env.log("  query a caldo ${run.warm.short()}")
                env.log("  memoria ${run.memAfter!!.short()} - picco PSS ${Fmt.mb(run.peakPss)}")

                // Il troncamento MRL fatto dal motore (outputSize) deve coincidere col nostro "primi N + normalizza".
                if (run === r440v) {
                    val mrl = JSONArray()
                    for (d in listOf(512, 256, 128)) {
                        val cs = ArrayList<Double>()
                        for (i in 0 until minOf(10, texts.size)) {
                            val e = env.em.embedText(texts[i], d).value
                            cs.add(VecMath.cosine(e, VecMath.truncateNormalize(run.vecs[i], d)))
                        }
                        env.log("  MRL $d (motore vs troncamento nostro): coseno medio ${Fmt.d(cs.average(), 6)}, minimo ${Fmt.d(cs.min(), 6)}")
                        mrl.put(JSONObject().put("dim", d).num("cosMean", cs.average(), 6).num("cosMin", cs.min(), 6))
                    }
                    run.mrl = mrl
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                run.error = causeChain(e)
                env.log("  ERRORE: ${run.error}")
            }
        }

        fun compare(label: String, a: TextRun, b: TextRun): List<Double>? {
            if (!a.ok || !b.ok) {
                env.log("  $label: non confrontabile (uno dei due motori non ha prodotto vettori)")
                return null
            }
            val n = minOf(a.vecs.size, b.vecs.size)
            val c = List(n) { VecMath.cosine(a.vecs[it], b.vecs[it]) }
            val mn = c.min()
            env.log("  $label: coseno medio ${Fmt.d(c.average(), 5)}, minimo ${Fmt.d(mn, 5)} su $n testi -> ${if (mn >= THRESHOLD) "OK (>= 0,99)" else "SOTTO SOGLIA 0,99"}")
            return c
        }

        env.log("Coerenza fra pacchetti (stessi testi)")
        val c270v440 = compare("270M vs 440M+visione", r270, r440v)
        val c270v440t = compare("270M vs 440M solo testo", r270, r440t)
        val c440 = compare("440M+visione vs 440M solo testo", r440v, r440t)

        // Classifica sulle foto indicizzate: la query fatta col 270M trova le stesse foto di quella col 440M?
        var overlapJson: JSONObject? = null
        if (index != null && r270.ok && r440v.ok) {
            val ranker = Ranker()
            val n = minOf(r270.vecs.size, r440v.vecs.size)
            val ov = List(n) { i ->
                overlap(
                    ranker.topK(index, r270.vecs[i], index.meta.dim, Precision.F32, 10),
                    ranker.topK(index, r440v.vecs[i], index.meta.dim, Precision.F32, 10),
                    10,
                ).toDouble()
            }
            env.log("  Classifica sull'indice (${index.size} foto): sovrapposizione top-10 270M vs 440M media ${Fmt.d(ov.average(), 2)}/10, minima ${Fmt.d(ov.min(), 0)}/10")
            overlapJson = JSONObject().put("indexSize", index.size).num("meanTop10Overlap", ov.average(), 2).num("minTop10Overlap", ov.min(), 0)
        } else {
            env.log("  (nessun indice caricato: salto il confronto delle classifiche)")
        }

        fun cosJson(c: List<Double>?): Any = if (c == null) JSONObject.NULL else JSONObject().put("n", c.size)
            .num("mean", c.average(), 5).num("min", c.min(), 5).put("perText", c.toJsonArray(5))

        fun runJson(r: TextRun) = JSONObject()
            .put("label", r.label).put("spec", r.spec.toJson())
            .put("backend", r.spec.backend.name)
            .put("error", r.error ?: JSONObject.NULL)
            .put("init", r.initInfo?.toJson() ?: JSONObject.NULL)
            .put("closeOfPreviousEngine", r.prevClose?.toJson() ?: JSONObject.NULL)
            .num("coldQueryMs", r.coldMs, 1)
            .num("firstQueryMs", r.latMs.firstOrNull() ?: Double.NaN, 1)
            .put("warmQueryMs", r.warm.toJson())
            .put("memoryAfterQueries", r.memAfter?.toJson() ?: JSONObject.NULL)
            .num("peakPssMb", r.peakPss, 1)
            .put("mrlEngineVsOurTruncation", r.mrl ?: JSONObject.NULL)
            .put("perQueryMs", r.latMs.toJsonArray())

        env.results.add(
            "model_compare",
            JSONObject()
                .put("backend", s.backend.name)
                .put("config", JSONObject().put("texts", texts.size).put("backend", s.backend.name).put("textOnlyMaxInputLength", len).put("threshold", THRESHOLD))
                .put("m270", runJson(r270)).put("m440WithVision", runJson(r440v)).put("m440TextOnly", runJson(r440t))
                .put("cos270vs440WithVision", cosJson(c270v440))
                .put("cos270vs440TextOnly", cosJson(c270v440t))
                .put("cos440WithVisionVs440TextOnly", cosJson(c440))
                .put("rankOverlapOnIndex", overlapJson ?: JSONObject.NULL),
        )
    }
}
