package dev.pampa.mycloudgallery.spike.embedprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

fun InitInfo.toJson(): JSONObject = JSONObject()
    .put("spec", spec.key)
    .num("initMs", initMs, 1)
    .put("cacheWasPopulated", cacheWasPopulated)
    .put("cacheBytesBefore", cacheBytesBefore)
    .put("cacheBytesAfter", cacheBytesAfter)
    .put("memBefore", memBefore.toJson())
    .put("memAfter", memAfter.toJson())
    .put("npuDiag", npuDiag ?: JSONObject.NULL)
    .put("logcat", JSONArray(logcat))

fun CloseInfo.toJson(): JSONObject = JSONObject()
    .num("closeMs", closeMs, 1).num("pssBeforeMb", pssBeforeMb, 1).num("pssAfterMb", pssAfterMb, 1)

fun EngineSpec.toJson(): JSONObject = JSONObject()
    .put("model", model.name).put("modelFile", model.fileName).put("backend", backend.name).put("withVision", withVision)
    .put("visionTokens", visionTokens).put("maxInputLength", maxInputLength).put("activation", activation.name)

object IndexWorkflow {
    const val LEGACY_INDEX_FILE = "index.bin"

    /** Un file per backend: l'indice NPU e quello GPU convivono senza sovrascriversi. */
    fun indexFile(ctx: android.content.Context, backend: String) = File(ctx.filesDir, "index-$backend.bin")

    suspend fun run(env: JobEnv, s: Settings, count: Int): IndexData? {
        val spec = s.visionSpec() // puo lanciare: NPU richiesta su un SoC senza file precompilato
        if (!env.store.isReady(spec.model)) {
            env.log("Modello ${spec.model.label} non scaricato/verificato: scaricalo prima")
            return null
        }
        val ids = withContext(Dispatchers.IO) { Photos.query(env.app, count) }
        if (ids.isEmpty()) {
            env.log("Nessuna foto trovata nella galleria")
            return null
        }
        env.log("Foto trovate: ${ids.size} (richieste $count). Backend richiesto: ${s.backend.label}, file ${spec.model.fileName}")

        env.setProgress(null, "Caricamento motore ${s.backend.label}...")
        val init = env.ensureEngine(spec)
        if (init != null) {
            env.log(
                "Motore pronto: initialize() ${Fmt.ms(init.initMs)} ms (cache compilazione " +
                    (if (init.cacheWasPopulated) "gia presente" else "vuota") + ") - ${spec.describe()}",
            )
            env.log("  memoria dopo init: ${init.memAfter.short()}")
            init.logcat.takeLast(8).forEach { env.log("  logcat: $it") }
        } else {
            env.log("Motore gia caricato con la stessa configurazione: riuso (initialize() non misurato)")
        }

        val thermal = env.probe.newThermalTracker().also { it.start() }
        try {
            val bat0 = env.probe.battery()
            val th0 = env.probe.thermalStatus()
            val head0 = env.probe.thermalHeadroom()
            val mem0 = env.probe.memory()
            var peakPss = mem0.pssMb
            var peakRss = mem0.rssMb
            var headMax = head0
            var battTempMax = bat0.tempC
            val samples = JSONArray()

            val decode = ArrayList<Double>()
            val convert = ArrayList<Double>()
            val embed = ArrayList<Double>()
            val embedCpu = ArrayList<Double>()
            val vecs = ArrayList<FloatArray>()
            val okIds = ArrayList<Long>()
            var failed = 0
            var badVectors = 0
            var firstError: String? = null
            var bytesSum = 0L
            var pixelsSum = 0L
            var minNorm = Double.POSITIVE_INFINITY
            var maxNorm = Double.NEGATIVE_INFINITY
            var stopped = false
            var processedOrFailed = 0
            val cpuProc0 = android.os.Process.getElapsedCpuTime()
            val wall0 = nowMs()

            coroutineScope {
                val channel = if (s.pipelined) Channel<Prepared>(2) else null
                val producer = channel?.let { ch ->
                    launch(Dispatchers.Default) {
                        try {
                            for ((pos, id) in ids.withIndex()) {
                                if (env.shouldStop()) break
                                ch.send(Photos.prepare(env.app, pos, id, s.res, s.convert))
                            }
                        } finally {
                            ch.close()
                        }
                    }
                }
                var pos = 0
                while (true) {
                    if (env.shouldStop()) {
                        stopped = true
                        break
                    }
                    val p: Prepared = if (channel != null) {
                        channel.receiveCatching().getOrNull() ?: break
                    } else {
                        if (pos >= ids.size) break
                        val cur = pos
                        withContext(Dispatchers.Default) { Photos.prepare(env.app, cur, ids[cur], s.res, s.convert) }
                    }
                    pos++
                    processedOrFailed++

                    val bytes = p.bytes
                    if (bytes == null) {
                        failed++
                        firstError = firstError ?: p.error
                        env.log("foto #${p.position}: decodifica/conversione fallita: ${p.error}")
                        if (failed >= 3) throw IllegalStateException("Troppi errori, interrompo: $firstError")
                        continue
                    }
                    val t = try {
                        env.em.embedImage(bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        val msg = causeChain(e)
                        firstError = firstError ?: msg
                        env.log("foto #${p.position}: embedding fallito (${s.backend.label}): $msg")
                        if (failed >= 3) throw IllegalStateException("Troppi errori, interrompo: $firstError")
                        null
                    } ?: continue

                    val v = t.value
                    val nrm = VecMath.norm(v)
                    if (VecMath.hasBadValues(v) || nrm == 0.0) {
                        badVectors++
                        env.log("foto #${p.position}: vettore con NaN/inf/zero, scartato")
                        continue
                    }
                    minNorm = min(minNorm, nrm)
                    maxNorm = max(maxNorm, nrm)
                    decode.add(p.decodeMs)
                    convert.add(p.convertMs)
                    embed.add(t.ms)
                    embedCpu.add(t.cpuMs)
                    vecs.add(v)
                    okIds.add(ids[p.position])
                    bytesSum += bytes.size
                    pixelsSum += p.width.toLong() * p.height

                    val done = vecs.size
                    if (done % 10 == 0) {
                        val m = env.probe.memory()
                        peakPss = max(peakPss, m.pssMb)
                        if (!m.rssMb.isNaN()) peakRss = max(peakRss, m.rssMb)
                    }
                    if (done % 25 == 0) {
                        val h = env.probe.thermalHeadroom()
                        if (!h.isNaN() && (headMax.isNaN() || h > headMax)) headMax = h
                        val b = env.probe.battery()
                        if (!b.tempC.isNaN() && (battTempMax.isNaN() || b.tempC > battTempMax)) battTempMax = b.tempC
                        samples.put(
                            JSONObject().put("n", done).num("headroom", h, 3).num("battTempC", b.tempC, 1)
                                .put("thermalStatus", env.probe.thermalStatus()).put("batteryPct", b.levelPct),
                        )
                    }
                    env.setProgress(
                        processedOrFailed.toFloat() / ids.size,
                        "Indicizzazione ${s.backend.label} $done/${ids.size} - ultima: dec ${Fmt.ms(p.decodeMs)} + conv ${Fmt.ms(p.convertMs)} + emb ${Fmt.ms(t.ms)} ms",
                    )
                }
                producer?.cancel()
            }
            val wallMs = nowMs() - wall0
            val cpuProcMs = (android.os.Process.getElapsedCpuTime() - cpuProc0).toDouble()

            val memEnd = env.probe.memory()
            peakPss = max(peakPss, memEnd.pssMb)
            val bat1 = env.probe.battery()
            if (!bat1.tempC.isNaN() && (battTempMax.isNaN() || bat1.tempC > battTempMax)) battTempMax = bat1.tempC
            val th1 = env.probe.thermalStatus()
            val head1 = env.probe.thermalHeadroom()
            if (!head1.isNaN() && (headMax.isNaN() || head1 > headMax)) headMax = head1

            val n = vecs.size
            if (n == 0) {
                env.log("Nessun vettore calcolato (errori: $failed, ${firstError ?: "-"})")
                env.results.add(
                    "index_failed",
                    JSONObject().put("backend", s.backend.name).put("failed", failed)
                        .put("firstError", firstError ?: JSONObject.NULL)
                        .put("config", JSONObject().put("spec", spec.toJson())),
                )
                return null
            }

            val seqMs = List(n) { decode[it] + convert[it] + embed[it] }
            val seg = if (n >= 20) min(50, n / 2) else 0
            fun segJson(from: List<Double>): JSONObject = JSONObject()
                .num("sequentialImgPerSec", imgPerSec(from), 2)
            val firstSeq = seqMs.take(seg)
            val lastSeq = seqMs.takeLast(seg)
            val firstEmb = embed.take(seg)
            val lastEmb = embed.takeLast(seg)
            val dropSeq = if (seg > 0) (1.0 - imgPerSec(lastSeq) / imgPerSec(firstSeq)) * 100.0 else Double.NaN
            val dropEmb = if (seg > 0) (1.0 - imgPerSec(lastEmb) / imgPerSec(firstEmb)) * 100.0 else Double.NaN

            val sDec = Summary.of(decode)
            val sConv = Summary.of(convert)
            val sEmb = Summary.of(embed)
            val sTot = Summary.of(seqMs)
            val wallImgS = 1000.0 * n / wallMs

            // CPU spesa dentro computeEmbedding: indica quanto il backend pesa sui core del telefono (1.0 = un core pieno).
            val embedCpuMsTotal = embedCpu.sum()
            val embedCpuCores = if (embed.sum() > 0) embedCpuMsTotal / embed.sum() else Double.NaN
            val procCpuCores = if (wallMs > 0) cpuProcMs / wallMs else Double.NaN

            // Batteria: valida solo scollegati. Il % intero e grossolano su poche foto, il contatore in mAh e piu fine.
            val batValid = !bat0.charging && !bat1.charging && bat0.levelPct >= 0 && bat1.levelPct >= 0
            val dropPct = (bat0.levelPct - bat1.levelPct).toDouble()
            val pctPer1000 = if (batValid) dropPct / n * 1000.0 else Double.NaN
            val dMah = if (batValid && bat0.chargeUah > 0 && bat1.chargeUah > 0) (bat0.chargeUah - bat1.chargeUah) / 1000.0 else Double.NaN
            val mahPer1000 = dMah / n * 1000.0
            val capacityMah = if (bat0.chargeUah > 0 && bat0.levelPct > 0) bat0.chargeUah / 1000.0 / (bat0.levelPct / 100.0) else Double.NaN
            val pctPer1000FromCounter = mahPer1000 / capacityMah * 100.0

            env.log("=== Indicizzazione ${s.backend.label}: $n vettori in ${Fmt.ms(wallMs / 1000.0)} s (falliti $failed, NaN $badVectors)${if (stopped) " [fermata dall'utente]" else ""}")
            env.log("  decodifica   ${sDec.short()}")
            env.log("  conversione  ${sConv.short()}")
            env.log("  embedding    ${sEmb.short()}")
            env.log("  totale/foto  ${sTot.short()}")
            env.log(
                "  immagini/s: solo embedding ${Fmt.ms(imgPerSec(embed))}, somma fasi ${Fmt.ms(imgPerSec(seqMs))}, " +
                    "orologio ${Fmt.ms(wallImgS)}${if (s.pipelined) " (pipeline attiva)" else ""}",
            )
            if (seg > 0) {
                env.log(
                    "  prime $seg vs ultime $seg: somma fasi ${Fmt.ms(imgPerSec(firstSeq))} -> ${Fmt.ms(imgPerSec(lastSeq))} img/s " +
                        "(calo ${Fmt.ms(dropSeq)}%), solo embedding ${Fmt.ms(imgPerSec(firstEmb))} -> ${Fmt.ms(imgPerSec(lastEmb))} (calo ${Fmt.ms(dropEmb)}%)",
                )
            }
            env.log("  CPU durante l'embedding: ${Fmt.d(embedCpuCores, 2)} core medi (${Fmt.ms(embedCpuMsTotal / n)} ms di CPU per foto); processo intero ${Fmt.d(procCpuCores, 2)} core")
            env.log("  termico: stato $th0 -> $th1 (max ${thermal.maxStatus}), headroom ${Fmt.d(head0, 2)} -> ${Fmt.d(head1, 2)} (max ${Fmt.d(headMax, 2)}), batteria ${Fmt.d(bat0.tempC, 1)} -> ${Fmt.d(bat1.tempC, 1)} C (max ${Fmt.d(battTempMax, 1)})")
            if (batValid) {
                env.log(
                    "  batteria: ${bat0.levelPct}% -> ${bat1.levelPct}% (stima ${Fmt.d(pctPer1000, 1)}% ogni 1000 foto; dal contatore: ${Fmt.d(dMah, 1)} mAh = " +
                        "${Fmt.d(mahPer1000, 1)} mAh e ${Fmt.d(pctPer1000FromCounter, 2)}% ogni 1000 foto). Con poche foto il % intero e molto grossolano.",
                )
            } else {
                env.log("  batteria: ${bat0.levelPct}% -> ${bat1.levelPct}% ma il dispositivo era in carica: consumo non misurabile (scollega il cavo per questa serie)")
            }
            env.log("  memoria: prima ${mem0.short()}")
            env.log("           dopo  ${memEnd.short()} - picco PSS ${Fmt.mb(peakPss)}")
            env.log("  norme vettori ${Fmt.d(minNorm, 5)}..${Fmt.d(maxNorm, 5)}, dimensione ${vecs[0].size}, immagine media ${bytesSum / n / 1024} KiB, ${pixelsSum / n / 1000} kpx")

            val data = IndexData(
                ids = okIds.toLongArray(),
                vecs = vecs.toTypedArray(),
                meta = IndexMeta(
                    modelKey = ModelId.M440.name,
                    backend = s.backend.name,
                    visionTokens = s.tokens,
                    resolution = s.res,
                    convert = s.convert.name,
                    dim = vecs[0].size,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            withContext(Dispatchers.IO) { IndexStore.save(indexFile(env.app, s.backend.name), data) }

            env.results.add(
                "index",
                JSONObject()
                    // Etichetta di serie: ogni evento porta il suo backend in cima, per separare le serie.
                    .put("backend", s.backend.name)
                    .put(
                        "backendUsed",
                        JSONObject()
                            .put("requested", s.backend.name)
                            .put("modelVariant", spec.model.name)
                            .put("modelFile", spec.model.fileName)
                            .put("engineInitialized", init != null)
                            .put("engineReusedFromPreviousRun", init == null)
                            .put(
                                "note",
                                "L'API Kotlin non espone il backend effettivo e l'app non ha ripiego: se initialize() riesce il runtime ha usato quello richiesto. " +
                                    "Controlli indipendenti: embedCpuCores (core CPU occupati durante computeEmbedding) e logcatHints.",
                            )
                            .num("embedCpuCores", embedCpuCores, 3)
                            .num("embedCpuMsPerImage", embedCpuMsTotal / n, 1)
                            .num("processCpuCores", procCpuCores, 3)
                            .put("logcatHints", JSONArray(init?.logcat ?: emptyList<String>())),
                    )
                    .put(
                        "config",
                        JSONObject().put("spec", spec.toJson()).put("resolution", s.res).put("convert", s.convert.name)
                            .put("pipelined", s.pipelined).put("requested", count),
                    )
                    .put("engineInit", init?.toJson() ?: JSONObject.NULL)
                    .put(
                        "counts",
                        JSONObject().put("photosFound", ids.size).put("embedded", n).put("failed", failed)
                            .put("badVectors", badVectors).put("stoppedByUser", stopped),
                    )
                    .put("firstError", firstError ?: JSONObject.NULL)
                    .put("decodeMs", sDec.toJson()).put("convertMs", sConv.toJson())
                    .put("embedMs", sEmb.toJson()).put("sumPhasesMs", sTot.toJson())
                    .put(
                        "throughput",
                        JSONObject().num("embedOnlyImgPerSec", imgPerSec(embed)).num("sumPhasesImgPerSec", imgPerSec(seqMs))
                            .num("wallImgPerSec", wallImgS).num("wallSeconds", wallMs / 1000.0),
                    )
                    .put(
                        "thermalDrop",
                        JSONObject().put("segment", seg)
                            .put("first", segJson(firstSeq).num("embedOnlyImgPerSec", imgPerSec(firstEmb)))
                            .put("last", segJson(lastSeq).num("embedOnlyImgPerSec", imgPerSec(lastEmb)))
                            .num("dropSumPhasesPct", dropSeq, 1).num("dropEmbedOnlyPct", dropEmb, 1),
                    )
                    .put(
                        "thermal",
                        JSONObject().put("statusStart", th0).put("statusEnd", th1).put("statusMax", thermal.maxStatus)
                            .num("headroomStart", head0, 3).num("headroomEnd", head1, 3).num("headroomMax", headMax, 3)
                            .num("batteryTempStartC", bat0.tempC, 1).num("batteryTempEndC", bat1.tempC, 1).num("batteryTempMaxC", battTempMax, 1)
                            .put("samplesEvery25Images", samples),
                    )
                    .put("batteryStart", bat0.toJson()).put("batteryEnd", bat1.toJson())
                    .put(
                        "batteryUse",
                        JSONObject().put("valid", batValid)
                            .put("note", if (batValid) "stima da inizio/fine run, grossolana su poche foto" else "dispositivo in carica: consumo non misurabile")
                            .num("levelDropPct", if (batValid) dropPct else Double.NaN, 0)
                            .num("pctPer1000Images", pctPer1000, 2)
                            .num("consumedMah", dMah, 2)
                            .num("mahPer1000Images", mahPer1000, 1)
                            .num("estimatedCapacityMah", capacityMah, 0)
                            .num("pctPer1000ImagesFromCounter", pctPer1000FromCounter, 3),
                    )
                    .put(
                        "memory",
                        JSONObject().put("beforeIndex", mem0.toJson()).put("afterIndex", memEnd.toJson())
                            .num("peakPssMb", peakPss, 1).num("peakRssMb", peakRss, 1),
                    )
                    .put(
                        "vectors",
                        JSONObject().put("dim", vecs[0].size).num("normMin", minNorm, 5).num("normMax", maxNorm, 5)
                            .num("avgEncodedKiB", bytesSum.toDouble() / n / 1024, 1).num("avgKpx", pixelsSum.toDouble() / n / 1000, 1),
                    )
                    .put(
                        "perImageMs",
                        JSONObject().put("decode", decode.toJsonArray()).put("convert", convert.toJsonArray())
                            .put("embed", embed.toJsonArray()).put("embedCpu", embedCpu.toJsonArray()),
                    ),
            )
            return data
        } finally {
            thermal.stop()
        }
    }
}
