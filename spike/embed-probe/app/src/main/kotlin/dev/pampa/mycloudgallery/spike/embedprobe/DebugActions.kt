package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Percorso di debug attivabile via adb, senza UI e SENZA toccare la galleria dell'utente:
 *
 *   adb shell am start -n dev.pampa.mycloudgallery.spike.embedprobe/.MainActivity \
 *       --es debugAction npuInit --es textBackend NPU --es visionBackend NPU --es model npu \
 *       --ei tokens 70 --ei maxLen 128 --ei reps 5 --es tag prova1
 *
 * Inizializza il motore con la configurazione richiesta (backend per modalita separati), calcola embedding di
 * pochi testi e di 3 immagini SINTETICHE generate qui, e li confronta con il riferimento CPU del 440M generico.
 * Scrive il risultato in getExternalFilesDir/debug-npuInit-<tag>-<timestamp>.json e su logcat (tag EmbedProbeDebug).
 * Nessun ripiego: se l'init fallisce, l'errore reale va nel JSON e nel log.
 */
object DebugActions {
    private const val TAG = "EmbedProbeDebug"

    fun handle(ctx: Context, intent: Intent?) {
        val action = intent?.getStringExtra("debugAction") ?: return
        intent.removeExtra("debugAction")
        val p = Params(
            textBackend = intent.getStringExtra("textBackend") ?: "NPU",
            visionBackend = intent.getStringExtra("visionBackend") ?: "NPU",
            model = intent.getStringExtra("model") ?: "npu",
            tokens = intent.getIntExtra("tokens", 70),
            maxLen = intent.getIntExtra("maxLen", 128),
            reps = intent.getIntExtra("reps", 5),
            reference = intent.getStringExtra("ref") ?: "CPU",
            tag = intent.getStringExtra("tag") ?: "run",
            texts = intent.getStringExtra("texts") ?: "short",
        )
        if (action != "npuInit") {
            Log.w(TAG, "azione sconosciuta: $action")
            return
        }
        // Thread unico per init, inferenza e close: come fa EngineManager.
        Thread({ runNpuInit(ctx.applicationContext, p) }, "embed-debug").start()
    }

    private class Params(
        val textBackend: String,
        val visionBackend: String,
        val model: String,
        val tokens: Int,
        val maxLen: Int,
        val reps: Int,
        val reference: String,
        val tag: String,
        val texts: String,
    )

    private class RunResult(val label: String) {
        var error: String? = null
        var errorLogcat: List<String> = emptyList()
        var initMs = Double.NaN
        var pssAfterInitMb = Double.NaN
        val textMs = ArrayList<Double>()
        val imageMs = ArrayList<Double>()
        val textVecs = ArrayList<FloatArray>()
        val imageVecs = ArrayList<FloatArray>()
        // seconda ripetizione: serve a vedere se il backend e deterministico
        val textVecs2 = ArrayList<FloatArray>()
        val imageVecs2 = ArrayList<FloatArray>()
        var cpuCoresText = Double.NaN
        var cpuCoresImage = Double.NaN
        var spec: JSONObject = JSONObject()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private fun backendOf(ctx: Context, name: String): Backend? = when (name.uppercase(Locale.US)) {
        "CPU" -> Backend.CPU()
        "GPU" -> Backend.GPU()
        "NPU" -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)
        "NONE" -> null
        else -> throw IllegalArgumentException("backend sconosciuto: $name")
    }

    private fun runNpuInit(ctx: Context, p: Params) {
        val out = JSONObject()
        val probe = DeviceProbe(ctx)
        val store = ModelStore(ctx)
        try {
            val testModel = if (p.model == "npu") ModelId.deviceNpu() else ModelId.M440
            log("START tag=${p.tag} text=${p.textBackend} vision=${p.visionBackend} model=${testModel?.fileName} tokens=${p.tokens} maxLen=${p.maxLen} reps=${p.reps} ref=${p.reference}")
            out.put("params", JSONObject().put("textBackend", p.textBackend).put("visionBackend", p.visionBackend)
                .put("model", testModel?.name ?: "none").put("tokens", p.tokens).put("maxLen", p.maxLen).put("reps", p.reps)
                .put("reference", p.reference).put("tag", p.tag))
            out.put("device", probe.deviceInfo())
            out.put("nativeLibraryDir", ctx.applicationInfo.nativeLibraryDir)
            out.put(
                "nativeLibs",
                JSONArray(File(ctx.applicationInfo.nativeLibraryDir).listFiles()?.map { "${it.name} (${it.length() / 1024} KiB)" }?.sorted() ?: emptyList<String>()),
            )
            if (testModel == null || !store.isReady(testModel)) {
                out.put("fatal", "modello ${testModel?.name} non pronto sul dispositivo")
                log("FATAL modello non pronto")
                return
            }

            val images = syntheticImages()
            val texts = textSet(p.texts)
            log("Immagini sintetiche: ${images.size} (${images.joinToString { "${it.size / 1024} KiB" }}), testi: ${texts.size}")

            val ref = if (p.reference.equals("none", true)) null
            else runConfig(ctx, probe, store, "ref-${p.reference}", ModelId.M440, p.reference, p.reference, p, images, texts)
            val test = runConfig(ctx, probe, store, "test", testModel, p.textBackend, p.visionBackend, p, images, texts)

            out.put("reference", ref?.let { toJson(it) } ?: JSONObject.NULL)
            out.put("test", toJson(test))
            if (ref != null && ref.error == null && test.error == null) {
                val cmp = JSONObject()
                cmp.put("textCosine", cosJson(ref.textVecs, test.textVecs))
                cmp.put("imageCosine", cosJson(ref.imageVecs, test.imageVecs))
                cmp.put("structure", structureJson(ref, test, texts))
                out.put("comparison", cmp)
                log("COSENO testo ${cmp.getJSONObject("textCosine")} immagini ${cmp.getJSONObject("imageCosine")}")
            }
            log("DONE tag=${p.tag} test.error=${test.error}")
        } catch (t: Throwable) {
            out.put("fatal", causeChain(t))
            Log.e(TAG, "FATAL ${causeChain(t)}", t)
        } finally {
            try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val f = File(dir, "debug-npuInit-${p.tag}-" + SimpleDateFormat("HHmmss", Locale.US).format(Date()) + ".json")
                f.writeText(out.toString(2))
                log("JSON scritto: ${f.path}")
            } catch (t: Throwable) {
                Log.e(TAG, "scrittura JSON fallita", t)
            }
        }
    }

    private fun runConfig(
        ctx: Context,
        probe: DeviceProbe,
        store: ModelStore,
        label: String,
        model: ModelId,
        textBackend: String,
        visionBackend: String,
        p: Params,
        images: List<ByteArray>,
        texts: List<String>,
    ): RunResult {
        val r = RunResult(label)
        r.spec = JSONObject().put("model", model.name).put("file", model.fileName).put("textBackend", textBackend)
            .put("visionBackend", visionBackend).put("tokens", p.tokens).put("maxLen", p.maxLen)
        log("--- $label: ${model.fileName} text=$textBackend vision=$visionBackend")
        val dir = File(ctx.cacheDir, "debug-engine/$label-${model.name}-$textBackend-$visionBackend-t${p.tokens}-l${p.maxLen}").apply { mkdirs() }
        val withVision = !visionBackend.equals("none", true)
        var engine: EmbeddingEngine? = null
        try {
            val cfg = EmbeddingEngineConfig(
                modelPath = store.file(model).path,
                backend = backendOf(ctx, textBackend)!!,
                visionBackend = backendOf(ctx, visionBackend),
                audioBackend = null,
                cacheDir = dir.path,
                maxInputLength = p.maxLen,
                visionTokensPerImage = if (withVision) p.tokens else null,
                activationDataType = null,
            )
            engine = EmbeddingEngine(cfg)
            val t0 = nowMs()
            try {
                engine.initialize()
            } catch (t: Throwable) {
                r.error = causeChain(t)
                r.errorLogcat = Logcat.recent(60)
                log("INIT FALLITO ($label): ${r.error}")
                r.errorLogcat.takeLast(25).forEach { log("  logcat: $it") }
                return r
            }
            r.initMs = nowMs() - t0
            r.pssAfterInitMb = probe.memory().pssMb
            log("init $label ok in ${Fmt.ms(r.initMs)} ms, ${probe.memory().short()}")
            Logcat.recent(15).forEach { log("  logcat: $it") }

            val opts = EmbeddingOptions(normalize = true)
            // Testi: la prima ripetizione e il vettore da confrontare, le altre servono alla latenza a caldo.
            var cpuText = 0.0
            var wallText = 0.0
            for (rep in 0 until p.reps) {
                for ((i, tx) in texts.withIndex()) {
                    val c0 = android.os.Process.getElapsedCpuTime()
                    val a = System.nanoTime()
                    val v = engine.computeEmbedding(listOf(InputData.Text(tx)), opts).embedding
                    val ms = (System.nanoTime() - a) / 1e6
                    cpuText += (android.os.Process.getElapsedCpuTime() - c0)
                    wallText += ms
                    r.textMs.add(ms)
                    if (rep == 0) r.textVecs.add(v) else if (rep == 1) r.textVecs2.add(v)
                    if (i == 0 && rep == 0) log("  primo testo ($label): ${Fmt.ms(ms)} ms")
                }
            }
            r.cpuCoresText = if (wallText > 0) cpuText / wallText else Double.NaN
            if (withVision) {
                var cpuImg = 0.0
                var wallImg = 0.0
                for (rep in 0 until p.reps) {
                    for ((i, img) in images.withIndex()) {
                        val c0 = android.os.Process.getElapsedCpuTime()
                        val a = System.nanoTime()
                        val v = engine.computeEmbedding(listOf(InputData.Image(img)), opts).embedding
                        val ms = (System.nanoTime() - a) / 1e6
                        cpuImg += (android.os.Process.getElapsedCpuTime() - c0)
                        wallImg += ms
                        r.imageMs.add(ms)
                        if (rep == 0) r.imageVecs.add(v) else if (rep == 1) r.imageVecs2.add(v)
                        if (i == 0 && rep == 0) log("  prima immagine ($label): ${Fmt.ms(ms)} ms")
                    }
                }
                r.cpuCoresImage = if (wallImg > 0) cpuImg / wallImg else Double.NaN
            }
            log("$label testi ${Summary.of(r.textMs).short()} - immagini ${Summary.of(r.imageMs).short()}")
        } catch (t: Throwable) {
            r.error = causeChain(t)
            r.errorLogcat = Logcat.recent(60)
            log("ERRORE ($label): ${r.error}")
            r.errorLogcat.takeLast(25).forEach { log("  logcat: $it") }
        } finally {
            try {
                engine?.close()
            } catch (_: Throwable) {
            }
        }
        return r
    }

    private fun toJson(r: RunResult): JSONObject {
        fun norms(v: List<FloatArray>) = v.map { VecMath.norm(it) }
        return JSONObject()
            .put("label", r.label).put("spec", r.spec)
            .put("error", r.error ?: JSONObject.NULL)
            .put("errorLogcat", JSONArray(r.errorLogcat))
            .num("initMs", r.initMs, 1).num("pssAfterInitMb", r.pssAfterInitMb, 1)
            .put("textMs", Summary.of(r.textMs).toJson())
            .put("imageMs", Summary.of(r.imageMs).toJson())
            .put("textMsPerCall", r.textMs.toJsonArray())
            .put("imageMsPerCall", r.imageMs.toJsonArray())
            .num("textCpuCores", r.cpuCoresText, 3).num("imageCpuCores", r.cpuCoresImage, 3)
            .put("textNorms", norms(r.textVecs).toJsonArray(5))
            .put("imageNorms", norms(r.imageVecs).toJsonArray(5))
            .put("badValues", (r.textVecs + r.imageVecs).count { VecMath.hasBadValues(it) })
            .put("dim", (r.textVecs.firstOrNull() ?: r.imageVecs.firstOrNull())?.size ?: 0)
            .put("repeatTextCosMin", repeatMin(r.textVecs, r.textVecs2))
            .put("repeatImageCosMin", repeatMin(r.imageVecs, r.imageVecs2))
            .put("textTextMatrix", matrix(r.textVecs, r.textVecs))
            .put("textImageMatrix", matrix(r.textVecs, r.imageVecs))
    }

    private fun repeatMin(a: List<FloatArray>, b: List<FloatArray>): Any {
        val n = minOf(a.size, b.size)
        if (n == 0) return JSONObject.NULL
        return Fmt.round((0 until n).minOf { VecMath.cosine(a[it], b[it]) }, 6)
    }

    private fun matrix(rows: List<FloatArray>, cols: List<FloatArray>): JSONArray {
        val m = JSONArray()
        for (r in rows) {
            val row = JSONArray()
            for (c in cols) row.put(Fmt.round(VecMath.cosine(r, c), 4))
            m.put(row)
        }
        return m
    }

    /** La struttura delle similarita si conserva anche se i vettori non coincidono? Pearson delle matrici e accordo sull'immagine piu vicina. */
    private fun structureJson(ref: RunResult, test: RunResult, texts: List<String>): JSONObject {
        fun flat(a: List<FloatArray>, b: List<FloatArray>, upper: Boolean): List<Double> {
            val out = ArrayList<Double>()
            for (i in a.indices) for (j in b.indices) if (!upper || j > i) out.add(VecMath.cosine(a[i], b[j]))
            return out
        }
        fun pearson(x: List<Double>, y: List<Double>): Double {
            if (x.size != y.size || x.size < 2) return Double.NaN
            val mx = x.average(); val my = y.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
            return if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / Math.sqrt(sxx * syy)
        }
        fun best(t: List<FloatArray>, im: List<FloatArray>) = t.map { tv -> im.indices.maxByOrNull { VecMath.cosine(tv, im[it]) } ?: -1 }
        val bestRef = best(ref.textVecs, ref.imageVecs)
        val bestTest = best(test.textVecs, test.imageVecs)
        return JSONObject()
            .num("pearsonTextTextUpper", pearson(flat(ref.textVecs, ref.textVecs, true), flat(test.textVecs, test.textVecs, true)), 4)
            .num("pearsonTextImage", pearson(flat(ref.textVecs, ref.imageVecs, false), flat(test.textVecs, test.imageVecs, false)), 4)
            .put("bestImagePerTextRef", JSONArray(bestRef))
            .put("bestImagePerTextTest", JSONArray(bestTest))
            .put("bestImageAgreement", bestRef.indices.count { bestRef[it] == bestTest[it] })
            .put("texts", JSONArray(texts.map { it.take(40) }))
    }

    private fun textSet(name: String): List<String> = when (name) {
        // i primi tre descrivono le immagini sintetiche, il quarto e un distrattore
        "short" -> listOf(
            "un cerchio rosso su un prato verde con il sole giallo nel cielo azzurro",
            "strisce verticali bianche e nere",
            "una casetta marrone con il tetto rosso e la porta gialla",
            "un gatto che dorme sul divano",
        )
        // stessi soggetti ma con frasi di lunghezza crescente (per escludere effetti del padding a 128 token)
        "long" -> listOf(
            "Nella foto si vede un grande cerchio rosso appoggiato su un prato verde brillante, mentre in alto a destra il sole giallo splende in un cielo azzurro e limpido, senza nessuna nuvola, in una bella giornata di primavera.",
            "L'immagine mostra una successione regolare di strisce verticali, alternate una bianca e una nera, che coprono tutta la superficie della fotografia in modo uniforme, come il disegno di una bandiera, di una zebra o di un codice a barre molto semplice.",
            "Questa fotografia ritrae una piccola casetta con le pareti marroni, un tetto rosso a punta e una porta gialla al centro, disegnata su uno sfondo celeste uniforme, un po' come farebbe un bambino quando disegna la propria casa dei sogni.",
            "Il gatto di casa dorme profondamente sul divano del soggiorno, arrotolato su se stesso accanto a un cuscino morbido, mentre fuori piove e nella stanza c'e un silenzio tranquillo, interrotto solo dal ticchettio dell'orologio appeso alla parete.",
        )
        else -> ItalianTexts.all.take(4)
    }

    private fun cosJson(a: List<FloatArray>, b: List<FloatArray>): JSONObject {
        val n = minOf(a.size, b.size)
        val c = List(n) { VecMath.cosine(a[it], b[it]) }
        return JSONObject().put("n", n).num("mean", if (c.isEmpty()) Double.NaN else c.average(), 5)
            .num("min", c.minOrNull() ?: Double.NaN, 5).put("perItem", c.toJsonArray(5))
    }

    // ---- immagini sintetiche (nessuna lettura della galleria) ----

    private fun syntheticImages(): List<ByteArray> {
        val w = 768
        val h = 576
        fun encode(draw: (Canvas, Paint) -> Unit): ByteArray {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            draw(c, paint)
            val bytes = Photos.encodeJpeg(bmp, 95)
            bmp.recycle()
            return bytes
        }
        // 1: cielo sfumato, prato verde, sole giallo e un cerchio rosso
        val a = encode { c, p ->
            p.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, Color.rgb(90, 160, 235), Color.rgb(200, 225, 250), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h * 0.7f, p)
            p.shader = null
            p.color = Color.rgb(60, 150, 60)
            c.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), p)
            p.color = Color.rgb(255, 215, 0)
            c.drawCircle(w * 0.8f, h * 0.2f, 60f, p)
            p.color = Color.RED
            c.drawCircle(w * 0.3f, h * 0.75f, 70f, p)
        }
        // 2: strisce verticali bianche e nere
        val b = encode { c, p ->
            p.color = Color.WHITE
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.color = Color.BLACK
            var x = 0f
            while (x < w) {
                c.drawRect(x, 0f, x + 32f, h.toFloat(), p)
                x += 64f
            }
        }
        // 3: casetta (rettangolo marrone, tetto rosso, porta gialla) su sfondo azzurro
        val d = encode { c, p ->
            p.color = Color.rgb(135, 206, 250)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.color = Color.rgb(139, 90, 43)
            c.drawRect(w * 0.25f, h * 0.45f, w * 0.75f, h * 0.9f, p)
            p.color = Color.rgb(178, 34, 34)
            val path = android.graphics.Path().apply {
                moveTo(w * 0.2f, h * 0.45f); lineTo(w * 0.5f, h * 0.15f); lineTo(w * 0.8f, h * 0.45f); close()
            }
            c.drawPath(path, p)
            p.color = Color.rgb(255, 215, 0)
            c.drawRect(w * 0.45f, h * 0.62f, w * 0.55f, h * 0.9f, p)
        }
        return listOf(a, b, d)
    }

    @Suppress("unused")
    private fun approxOne(x: Double) = abs(x - 1.0) < 1e-3
}
