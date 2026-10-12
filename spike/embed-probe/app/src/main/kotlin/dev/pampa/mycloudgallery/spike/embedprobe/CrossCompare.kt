package dev.pampa.mycloudgallery.spike.embedprobe

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** 30 query italiane generiche: oggetti, animali, scene, persone, cibo, testo/screenshot, colori. */
object CrossQueries {
    val all: List<String> = listOf(
        // animali
        "un cane", "un gatto", "un cavallo in un prato", "uccelli nel cielo",
        // scene e paesaggi
        "una spiaggia con il mare", "montagne con la neve", "un tramonto", "un bosco in autunno",
        "un lago o un fiume", "una citta di notte con le luci",
        // persone
        "una persona che sorride", "un gruppo di amici", "bambini che giocano", "un neonato", "una coppia di sposi",
        // cibo
        "una pizza", "un piatto di pasta", "una torta", "frutta e verdura", "una tazza di caffe",
        // oggetti e luoghi
        "una macchina", "una bicicletta", "un aereo", "una chiesa o un edificio antico", "fiori colorati",
        // testo e screenshot
        "uno screenshot del telefono", "un documento con del testo", "uno scontrino",
        // colori e stile
        "qualcosa di rosso", "una foto in bianco e nero",
    )
}

/**
 * Confronto incrociato fra backend: gli indici (NPU / GPU / CPU) costruiti sulle STESSE foto vengono interrogati con
 * query calcolate da ogni backend disponibile (e dal 270M su CPU). Per ogni indice il riferimento e "stesso backend"
 * (indice X interrogato con query X); le altre coppie si misurano contro di esso: sovrapposizione @5/@10/@20 e
 * correlazione di rango (Spearman sull'intera classifica), piu il riepilogo di quanto sono separati i punteggi.
 */
object CrossCompare {
    private val KS = listOf(5, 10, 20)

    private class Source(val key: String, val label: String, val spec: EngineSpec) {
        var error: String? = null
        var initMs = Double.NaN
        val vecs = ArrayList<FloatArray>()
        val embedMs = ArrayList<Double>()
        val ok: Boolean get() = error == null && vecs.isNotEmpty()
    }

    /** Punteggi di tutte le foto per ogni query, per una coppia (indice, sorgente della query). */
    private class Combo(val index: String, val query: String, val scores: Array<DoubleArray>) {
        val order: Array<IntArray> = Array(scores.size) { q ->
            val sc = scores[q]
            sc.indices.sortedByDescending { sc[it] }.toIntArray()
        }
        val rank: Array<IntArray> = Array(scores.size) { q ->
            val r = IntArray(scores[q].size)
            order[q].forEachIndexed { pos, photo -> r[photo] = pos }
            r
        }
        val label get() = "indice $index / query $query"
    }

    private class Metrics(val ov: Map<Int, DoubleArray>, val rho: DoubleArray)

    private fun metrics(ref: Combo, other: Combo): Metrics {
        val nq = ref.scores.size
        val ov = KS.associateWith { k ->
            DoubleArray(nq) { q ->
                val a = ref.order[q].take(k).toSet()
                other.order[q].take(k).count { it in a }.toDouble() / k
            }
        }
        val rho = DoubleArray(nq) { q ->
            val n = ref.rank[q].size.toDouble()
            var d2 = 0.0
            for (i in ref.rank[q].indices) {
                val d = (ref.rank[q][i] - other.rank[q][i]).toDouble()
                d2 += d * d
            }
            1.0 - 6.0 * d2 / (n * (n * n - 1.0))
        }
        return Metrics(ov, rho)
    }

    private fun stat(v: DoubleArray, dec: Int = 4) = JSONObject()
        .num("mean", if (v.isEmpty()) Double.NaN else v.average(), dec)
        .num("min", v.minOrNull() ?: Double.NaN, dec)
        .num("max", v.maxOrNull() ?: Double.NaN, dec)

    private fun metricsJson(m: Metrics) = JSONObject()
        .put("overlap", JSONObject().apply { KS.forEach { k -> put("at$k", stat(m.ov.getValue(k))) } })
        .put("spearman", stat(m.rho))

    private fun metricsPerQueryJson(m: Metrics) = JSONObject()
        .apply { KS.forEach { k -> put("overlapAt$k", m.ov.getValue(k).toList().toJsonArray(3)) } }
        .put("spearman", m.rho.toList().toJsonArray(4))

    private fun metricsLine(m: Metrics): String {
        val parts = KS.map { k ->
            val v = m.ov.getValue(k)
            "@$k ${Fmt.d(v.average() * k, 1)}/$k (min ${Fmt.d((v.minOrNull() ?: 0.0) * k, 0)})"
        }
        return parts.joinToString("  ") + "  rho ${Fmt.d(m.rho.average(), 3)} (min ${Fmt.d(m.rho.minOrNull() ?: Double.NaN, 3)})"
    }

    private fun scoreSummary(c: Combo): JSONObject {
        val nq = c.scores.size
        val n = c.scores[0].size
        val top1 = DoubleArray(nq) { c.scores[it][c.order[it][0]] }
        val top20 = DoubleArray(nq) { c.scores[it][c.order[it][min(19, n - 1)]] }
        val median = DoubleArray(nq) { c.scores[it][c.order[it][n / 2]] }
        return JSONObject()
            .num("top1Mean", top1.average(), 4).num("top20Mean", top20.average(), 4)
            .num("gapTop1ToTop20Mean", (top1.indices.map { top1[it] - top20[it] }).average(), 4)
            .num("medianAllMean", median.average(), 4)
            .num("top1MinusMedianMean", (top1.indices.map { top1[it] - median[it] }).average(), 4)
    }

    private fun scoreLine(c: Combo): String {
        val nq = c.scores.size
        val n = c.scores[0].size
        val top1 = (0 until nq).map { c.scores[it][c.order[it][0]] }
        val top20 = (0 until nq).map { c.scores[it][c.order[it][min(19, n - 1)]] }
        val med = (0 until nq).map { c.scores[it][c.order[it][n / 2]] }
        return "top1 ${Fmt.d(top1.average(), 3)}, top20 ${Fmt.d(top20.average(), 3)}, distanza top1-top20 ${Fmt.d(top1.average() - top20.average(), 3)}, mediana ${Fmt.d(med.average(), 3)}"
    }

    suspend fun run(env: JobEnv, s: Settings, indexes: Map<String, IndexData>) {
        if (indexes.isEmpty()) {
            env.log("Nessun indice: costruiscine almeno uno con Indicizza foto (idealmente uno per backend, stesse foto, stesso N)")
            return
        }
        val preferred = listOf(BackendKind.NPU.name, BackendKind.GPU.name, BackendKind.CPU.name)
        val keys = preferred.filter { it in indexes } + indexes.keys.filter { it !in preferred }

        // --- 1. allineamento: solo le foto presenti in tutti gli indici, nell'ordine del primo
        val sets = keys.associateWith { indexes.getValue(it).ids.toSet() }
        val common = indexes.getValue(keys[0]).ids.filter { id -> keys.all { id in sets.getValue(it) } }.toLongArray()
        val sameOrder = keys.all { indexes.getValue(it).ids.contentEquals(common) }
        env.log("=== Confronto incrociato: indici ${keys.joinToString { "$it (${indexes.getValue(it).size})" }}; foto comuni ${common.size}; " +
            if (sameOrder) "stessa lista nello stesso ordine" else "liste diverse: uso solo le comuni")
        if (common.size < 20) {
            env.log("Troppe poche foto in comune per un confronto sensato (${common.size}). Rifai gli indici con lo stesso N e la stessa galleria.")
            return
        }
        val rowOf = keys.associateWith { k -> indexes.getValue(k).ids.withIndex().associate { (i, id) -> id to i } }
        val mats: Map<String, Array<FloatArray>> = keys.associateWith { k ->
            val idx = indexes.getValue(k)
            Array(common.size) { i -> idx.vecs[rowOf.getValue(k).getValue(common[i])] }
        }
        // Gli indici sono confrontabili solo se costruiti con le stesse impostazioni di anteprima.
        val metaWarnings = ArrayList<String>()
        val m0 = indexes.getValue(keys[0]).meta
        for (k in keys.drop(1)) {
            val m = indexes.getValue(k).meta
            if (m.visionTokens != m0.visionTokens || m.resolution != m0.resolution || m.convert != m0.convert) {
                val w = "indice $k: ${m.visionTokens} token/${m.resolution} px/${m.convert} contro ${keys[0]}: ${m0.visionTokens}/${m0.resolution}/${m0.convert}"
                metaWarnings.add(w)
                env.log("ATTENZIONE impostazioni diverse fra indici: $w")
            }
        }

        // --- 2. sorgenti delle query: ogni backend con il 440M pronto, piu il 270M su CPU
        val sources = ArrayList<Source>()
        for (b in listOf(BackendKind.NPU, BackendKind.GPU, BackendKind.CPU)) {
            val spec = try {
                s.visionSpec(b)
            } catch (_: IllegalStateException) {
                env.log("Query ${b.label}: non disponibile su questo SoC, salto")
                continue
            }
            if (!env.store.isReady(spec.model)) {
                env.log("Query ${b.label}: modello ${spec.model.label} non scaricato, salto")
                continue
            }
            sources.add(Source(b.name, "${b.label} 440M", spec))
        }
        if (env.store.isReady(ModelId.M270)) {
            sources.add(Source("M270_CPU", "270M CPU", EngineSpec(ModelId.M270, BackendKind.CPU, false, 0, 128, ActKind.AUTO)))
        } else {
            env.log("Query 270M: modello non scaricato, salto")
        }

        val queries = CrossQueries.all
        val formatted = queries.map { s.prefix.apply(it) }
        for ((si, src) in sources.withIndex()) {
            if (env.shouldStop()) break
            env.log("--- query ${src.label}: ${src.spec.describe()}")
            try {
                env.em.close()
                env.setProgress(null, "${src.label}: caricamento motore...")
                val init = env.ensureEngine(src.spec)
                src.initMs = init?.initMs ?: Double.NaN
                for ((qi, q) in formatted.withIndex()) {
                    if (env.shouldStop()) break
                    val t = env.em.embedText(q)
                    if (VecMath.hasBadValues(t.value)) throw IllegalStateException("vettore con NaN/inf alla query $qi")
                    src.vecs.add(t.value)
                    src.embedMs.add(t.ms)
                    env.setProgress((si + (qi + 1f) / formatted.size) / sources.size, "Query ${src.label}: ${qi + 1}/${formatted.size}")
                }
                if (src.vecs.size != queries.size) src.error = "interrotto a ${src.vecs.size}/${queries.size} query"
                env.log("  init ${Fmt.ms(src.initMs)} ms, ${Summary.of(src.embedMs).short()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                src.error = causeChain(e)
                env.log("  ERRORE ${src.label}: ${src.error}")
            }
        }
        val usable = sources.filter { it.ok && it.vecs.size == queries.size }
        if (usable.isEmpty()) {
            env.log("Nessuna sorgente di query utilizzabile")
            return
        }

        // --- 3. punteggi di tutte le foto per ogni coppia (indice, sorgente)
        env.setProgress(null, "Calcolo punteggi e classifiche...")
        val combos = LinkedHashMap<Pair<String, String>, Combo>()
        for (x in keys) {
            for (y in usable) {
                val mat = mats.getValue(x)
                val scores = Array(queries.size) { q -> DoubleArray(mat.size) { i -> VecMath.cosine(mat[i], y.vecs[q]) } }
                combos[x to y.key] = Combo(x, y.key, scores)
            }
        }

        // --- 4. confronto con il riferimento "stesso backend" di ciascun indice
        val comboJson = JSONArray()
        for (x in keys) {
            val ref = combos[x to x]
            env.log("Indice $x" + if (ref == null) " (nessuna query dello stesso backend: niente riferimento)" else " - riferimento = query $x")
            for (y in usable) {
                val c = combos.getValue(x to y.key)
                val isRef = y.key == x
                val m = if (ref != null && !isRef) metrics(ref, c) else null
                env.log("  query ${y.label.padEnd(10)}" + (if (isRef) " [riferimento]" else "") + ": " + scoreLine(c))
                if (m != null) env.log("      vs riferimento: ${metricsLine(m)}")
                comboJson.put(
                    JSONObject()
                        .put("index", x).put("query", y.key).put("isReference", isRef)
                        .put("referenceQuery", if (ref != null) x else JSONObject.NULL)
                        .put("scores", scoreSummary(c))
                        .put("vsReference", m?.let { metricsJson(it) } ?: JSONObject.NULL)
                        .put(
                            "perQuery",
                            JSONObject()
                                .put("top1", (0 until queries.size).map { c.scores[it][c.order[it][0]] }.toJsonArray(4))
                                .put("top20", (0 until queries.size).map { c.scores[it][c.order[it][min(19, mats.getValue(x).size - 1)]] }.toJsonArray(4))
                                .put("topPositions", JSONArray().also { arr -> c.order.forEach { o -> arr.put(o.take(20).toJsonIntArray()) } })
                                .apply { if (m != null) put("vsReference", metricsPerQueryJson(m)) },
                        ),
                )
            }
        }

        // --- 5. confronti fra sistemi: riferimenti di indici diversi, e stessa query su indici diversi
        val systemJson = JSONArray()
        env.log("Sistemi a confronto (riferimento di un indice contro quello di un altro)")
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = combos[keys[i] to keys[i]]
            val b = combos[keys[j] to keys[j]]
            if (a == null || b == null) continue
            val m = metrics(a, b)
            env.log("  ${a.label} vs ${b.label}: ${metricsLine(m)}")
            systemJson.put(JSONObject().put("a", "${keys[i]}/${keys[i]}").put("b", "${keys[j]}/${keys[j]}").put("metrics", metricsJson(m)))
        }
        val sameQueryJson = JSONArray()
        env.log("Stessa query su indici diversi (effetto del solo backend dell'indice)")
        for (y in usable) for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = combos.getValue(keys[i] to y.key)
            val b = combos.getValue(keys[j] to y.key)
            val m = metrics(a, b)
            env.log("  query ${y.label}: indice ${keys[i]} vs ${keys[j]}: ${metricsLine(m)}")
            sameQueryJson.put(JSONObject().put("query", y.key).put("indexA", keys[i]).put("indexB", keys[j]).put("metrics", metricsJson(m)))
        }

        // --- 6. JSON
        val sourcesJson = JSONObject()
        for (src in sources) {
            sourcesJson.put(
                src.key,
                JSONObject().put("label", src.label).put("spec", src.spec.toJson()).put("error", src.error ?: JSONObject.NULL)
                    .num("initMs", src.initMs, 1).put("embedMs", Summary.of(src.embedMs).toJson()),
            )
        }
        val indexJson = JSONObject()
        for (k in keys) {
            val m = indexes.getValue(k).meta
            indexJson.put(
                k,
                JSONObject().put("size", indexes.getValue(k).size).put("model", m.modelKey).put("backend", m.backend)
                    .put("visionTokens", m.visionTokens).put("resolution", m.resolution).put("convert", m.convert)
                    .put("createdAtMs", m.createdAtMs),
            )
        }
        env.results.add(
            "cross_compare",
            JSONObject()
                .put("backend", "CROSS")
                .put(
                    "config",
                    JSONObject().put("queries", queries.size).put("prefixFormat", s.prefix.name).put("dim", 768).put("precision", "F32")
                        .put("topKs", KS.toJsonIntArray()).put("note", "overlap = frazione di foto in comune nel top-k rispetto al riferimento (stesso backend); spearman sull'intera classifica"),
                )
                .put(
                    "alignment",
                    JSONObject().put("indexes", JSONArray(keys)).put("common", common.size).put("sameListSameOrder", sameOrder)
                        .put("settingsWarnings", JSONArray(metaWarnings))
                        .put("positionsRefer", "ordine delle foto comuni (data di aggiunta decrescente); nessun nome di file"),
                )
                .put("indexes", indexJson)
                .put("querySources", sourcesJson)
                .put("queryTexts", JSONArray(queries))
                .put("combos", comboJson)
                .put("systemLevel", systemJson)
                .put("sameQueryAcrossIndexes", sameQueryJson),
        )
        // il motore dell'ultima sorgente resta caricato: lo libera "Libera motore"
    }
}
