package dev.pampa.mycloudgallery.core.ai.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le MISURE dei motori dei volti (Fase 8, P2; docs/fase8-progetto.md D10, §2.12 "Prestazioni da misurare", F104): verifica delle impronte, avvio a freddo e
 * a caldo, YuNet a 1024 px (p50 e p95), la seconda passata a quattro tasselli, SFace per volto (p50 e p95), allineamento e qualita', la decodifica di
 * `ModelInputs.forFaces`, PSS e le foto al secondo da cui nasce `DeviceMeasures.facesPerSecond`. Non asserisce numeri (dipendono dal dispositivo e dal caldo):
 * li scrive nel log `P2Measure` (una riga JSON per misura) e in `files/p2-measures.jsonl`, e il resoconto di F104 li riporta in `docs/decisioni.md` con il
 * dispositivo e la data (S25, Tab S9, emulatore: sull'emulatore la CPU e' quella del PC, **non** del telefono).
 *
 * Il costo di YuNet e di SFace non dipende da cio' che c'e' nell'immagine (reti dense): le misure usano immagini disegnate dalla prova e volti allineati
 * disegnati, senza volti veri. Le corse con i volti veri delle composizioni (se l'operatore le ha messe in `/data/local/tmp/mcg-faces/`) si aggiungono a parte.
 * "Avvio a freddo" = primo uso del motore nel processo, dopo `release()` e con il file del modello ancora nella cache del sistema (la cache dei programmi
 * compilati, che MagicTouch e LiteRT-LM salvano, qui non c'e': i due modelli sono piccoli e l'interprete parte da zero).
 */
@RunWith(AndroidJUnit4::class)
class FaceMeasurementsDeviceTest {

  private fun scene(width: Int, height: Int, seed: Int = 0): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (y in 0 until height step 4) {
      paint.color = Color.rgb((y * 255 / height + seed * 20) % 256, 120, 255 - y * 255 / height)
      canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 4).toFloat(), paint)
    }
    paint.color = Color.rgb(250, 220, 40)
    canvas.drawCircle(width * 0.7f, height * 0.3f, height * 0.18f, paint)
    paint.color = Color.rgb(20, 20, 20)
    canvas.drawRect(width * 0.1f, height * 0.6f, width * 0.4f, height * 0.9f, paint)
    return bitmap
  }

  private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()

  private fun preview(seed: Int = 0): FaceImage {
    val bitmap = scene(1024, 768, seed)
    val bytes = jpeg(bitmap, 90)
    bitmap.recycle()
    return checkNotNull(ModelInputs.forFaces(bytes, FaceEngineLimits.DETECT_SIDE))
  }

  private fun hq(): FaceImage {
    val bitmap = scene(2048, 1536)
    val bytes = jpeg(bitmap, 85)
    bitmap.recycle()
    return checkNotNull(ModelInputs.forFaces(bytes, FaceEngineLimits.HQ_SIDE))
  }

  /** Un volto allineato disegnato (nessun volto vero). */
  private fun aligned(seed: Int): AlignedFace {
    val side = FaceEngineLimits.ALIGNED_SIDE
    val random = Random(seed.toLong() * 104729 + 7)
    val base = intArrayOf(110 + random.nextInt(110), 90 + random.nextInt(110), 80 + random.nextInt(110))
    return AlignedFace(
      IntArray(side * side) { i ->
        val x = i % side
        val y = i / side
        val inFace = ((x - 56) / 38.0) * ((x - 56) / 38.0) + ((y - 60) / 46.0) * ((y - 60) / 46.0) <= 1.0
        val n = random.nextInt(25) - 12
        val c = IntArray(3) { k -> (if (inFace) base[k] else 50 + seed) + n }
        (0xFF shl 24) or (c[0].coerceIn(0, 255) shl 16) or (c[1].coerceIn(0, 255) shl 8) or c[2].coerceIn(0, 255)
      },
    )
  }

  /** Cinque punti "da volto" dentro il riquadro (x, y, w, h): occhi, naso, bocca. */
  private fun faceLandmarks(x: Float, y: Float, w: Float, h: Float): FloatArray = floatArrayOf(
    x + w * 0.30f, y + h * 0.40f,
    x + w * 0.70f, y + h * 0.40f,
    x + w * 0.50f, y + h * 0.60f,
    x + w * 0.35f, y + h * 0.80f,
    x + w * 0.65f, y + h * 0.80f,
  )

  private fun thermalStatus(): Int =
    (FacesDevice.context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

  // --- Verifica dell'impronta -----------------------------------------------------------------------------------------------------

  @Test
  fun verificaDellImprontaDiOgniApertura() = runBlocking<Unit> {
    val files = FacesDevice.files()
    for (spec in ModelCatalog.FACES) {
      val times = (1..8).map {
        val start = System.nanoTime()
        val model = checkNotNull(files.open(spec.id)) { "il modello ${spec.id} non passa la verifica" }
        val elapsed = System.nanoTime() - start
        model.close()
        FacesDevice.ms(elapsed)
      }.sorted()
      FacesDevice.record("verifica_sha256_${spec.id}") {
        put("dimensione_mib", spec.sizeBytes / 1048576.0)
        put("ms_p50", FacesDevice.percentile(times, 0.5))
        put("ms_p95", FacesDevice.percentile(times, 0.95))
        put("ms_max", times.last())
        put("mib_al_secondo", spec.sizeBytes / 1048576.0 / (FacesDevice.percentile(times, 0.5) / 1000.0))
      }
    }
  }

  // --- Avvio a freddo e a caldo, PSS ----------------------------------------------------------------------------------------------

  private suspend fun coldWarm(label: String, spec: ModelSpec, use: suspend () -> Unit, release: suspend () -> Unit) {
    val files = FacesDevice.files()
    release()
    val pssBefore = FacesDevice.pssMb()
    val verifyStart = System.nanoTime()
    checkNotNull(files.open(spec.id)).close()
    val verifyMs = FacesDevice.ms(System.nanoTime() - verifyStart)
    val t0 = System.nanoTime()
    use() // verifica + mappatura + interprete + prima corsa
    val coldMs = FacesDevice.ms(System.nanoTime() - t0)
    val pssLoaded = FacesDevice.pssMb()
    val t1 = System.nanoTime()
    use() // a motore caldo
    val hotMs = FacesDevice.ms(System.nanoTime() - t1)
    release()
    val t2 = System.nanoTime()
    use() // dopo un rilascio: riapre (il file e' gia' nella cache di sistema)
    val warmStartMs = FacesDevice.ms(System.nanoTime() - t2)
    release()
    val pssAfter = FacesDevice.pssMb()
    FacesDevice.record("avvio_$label") {
      put("verifica_ms", verifyMs)
      put("avvio_a_freddo_con_prima_corsa_ms", coldMs)
      put("corsa_a_caldo_ms", hotMs)
      put("riavvio_dopo_rilascio_con_prima_corsa_ms", warmStartMs)
      put("pss_prima_mb", pssBefore)
      put("pss_caricato_mb", pssLoaded)
      put("pss_dopo_rilascio_mb", pssAfter)
    }
  }

  @Test
  fun avvioAFreddoEACaldoDiYuNet() = runBlocking<Unit> {
    val detector = YuNetDetector.create(FacesDevice.files())
    val image = preview()
    coldWarm("yunet", ModelCatalog.FACE_YUNET, { detector.detect(image, DetectPass.PREVIEW) }, { detector.release() })
  }

  @Test
  fun avvioAFreddoEACaldoDiSFace() = runBlocking<Unit> {
    val embedder = SFaceEmbedder.create(FacesDevice.files())
    val face = aligned(1)
    coldWarm("sface", ModelCatalog.FACE_SFACE, { embedder.embed(listOf(face)) }, { embedder.release() })
  }

  // --- YuNet ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun yuNetA1024ElaSecondaPassataATasselli() = runBlocking<Unit> {
    val detector = YuNetDetector.create(FacesDevice.files())
    val images = (0 until 4).map(::preview)
    val big = hq()
    try {
      repeat(5) { detector.detect(images[it % 4], DetectPass.PREVIEW) } // riscaldamento
      val total = ArrayList<Double>()
      val infer = ArrayList<Double>()
      val prepare = ArrayList<Double>()
      val decode = ArrayList<Double>()
      repeat(40) { i ->
        val t0 = System.nanoTime()
        detector.detect(images[i % 4], DetectPass.PREVIEW)
        total += FacesDevice.ms(System.nanoTime() - t0)
        val t = detector.lastTimings!!
        infer += t.inferMs.toDouble()
        prepare += t.prepareMs.toDouble()
        decode += t.decodeMs.toDouble()
      }
      total.sort()
      FacesDevice.record("yunet_1024_anteprima_1024x768") {
        put("ms_p50", FacesDevice.percentile(total, 0.5))
        put("ms_p95", FacesDevice.percentile(total, 0.95))
        put("modello_ms_p50", FacesDevice.percentile(infer.sorted(), 0.5))
        put("quadro_ms_p50", FacesDevice.percentile(prepare.sorted(), 0.5))
        put("decodifica_ms_p50", FacesDevice.percentile(decode.sorted(), 0.5))
        put("giri", total.size)
      }
      val hqTimes = ArrayList<Double>()
      val hqInfer = ArrayList<Double>()
      repeat(2) { detector.detect(big, DetectPass.HQ_TILES) }
      repeat(15) {
        val t0 = System.nanoTime()
        detector.detect(big, DetectPass.HQ_TILES)
        hqTimes += FacesDevice.ms(System.nanoTime() - t0)
        hqInfer += detector.lastTimings!!.inferMs.toDouble()
      }
      hqTimes.sort()
      FacesDevice.record("yunet_hq_4_tasselli_2048x1536") {
        put("ms_p50", FacesDevice.percentile(hqTimes, 0.5))
        put("ms_p95", FacesDevice.percentile(hqTimes, 0.95))
        put("modello_ms_p50", FacesDevice.percentile(hqInfer.sorted(), 0.5))
        put("tasselli", detector.lastTimings!!.tiles)
      }
      assertTrue(total.isNotEmpty())
    } finally {
      detector.release()
    }
  }

  // --- SFace, allineamento, qualita', ingresso -------------------------------------------------------------------------------------

  @Test
  fun sFacePerVoltoAllineamentoEQualita() = runBlocking<Unit> {
    val embedder = SFaceEmbedder.create(FacesDevice.files())
    val faces = (0 until 6).map(::aligned)
    val image = preview()
    try {
      repeat(5) { embedder.embed(listOf(faces[it % 6])) }
      val single = ArrayList<Double>()
      repeat(60) { i ->
        val t0 = System.nanoTime()
        val v = embedder.embed(listOf(faces[i % 6])).single()
        single += FacesDevice.ms(System.nanoTime() - t0)
        assertNotNull(v)
      }
      single.sort()
      FacesDevice.record("sface_per_volto_uno_alla_volta") {
        put("ms_p50", FacesDevice.percentile(single, 0.5))
        put("ms_p95", FacesDevice.percentile(single, 0.95))
        put("giri", single.size)
      }
      val batch = ArrayList<Double>()
      repeat(20) {
        val t0 = System.nanoTime()
        embedder.embed(faces)
        batch += FacesDevice.ms(System.nanoTime() - t0) / faces.size
      }
      batch.sort()
      FacesDevice.record("sface_per_volto_in_un_lotto_di_6") {
        put("ms_per_volto_p50", FacesDevice.percentile(batch, 0.5))
        put("ms_per_volto_p95", FacesDevice.percentile(batch, 0.95))
      }
      val landmarks = faceLandmarks(300f, 200f, 300f, 360f)
      val align = ArrayList<Double>()
      val quality = ArrayList<Double>()
      var last: AlignedFace? = null
      repeat(60) {
        val a = System.nanoTime()
        last = FaceAligner.align(image, landmarks)
        val b = System.nanoTime()
        FaceQuality.sharpnessOf(last!!)
        val c = System.nanoTime()
        align += FacesDevice.ms(b - a)
        quality += FacesDevice.ms(c - b)
      }
      FacesDevice.record("allineamento_e_nitidezza") {
        put("allineamento_ms_p50", FacesDevice.percentile(align.sorted(), 0.5))
        put("allineamento_ms_p95", FacesDevice.percentile(align.sorted(), 0.95))
        put("nitidezza_ms_p50", FacesDevice.percentile(quality.sorted(), 0.5))
      }
    } finally {
      embedder.release()
    }
  }

  @Test
  fun decodificaDellAnteprimaEDelHq() {
    val small = scene(1024, 768).let { b -> jpeg(b, 90).also { b.recycle() } }
    val big = scene(2048, 1536).let { b -> jpeg(b, 85).also { b.recycle() } }
    for ((name, bytes, side) in listOf(Triple("anteprima_1024_jpeg", small, FaceEngineLimits.DETECT_SIDE), Triple("hq_2048_jpeg", big, FaceEngineLimits.HQ_SIDE))) {
      repeat(3) { ModelInputs.forFaces(bytes, side) }
      val times = (0 until 25).map {
        val t0 = System.nanoTime()
        assertNotNull(ModelInputs.forFaces(bytes, side))
        FacesDevice.ms(System.nanoTime() - t0)
      }.sorted()
      FacesDevice.record("forFaces_$name") {
        put("ms_p50", FacesDevice.percentile(times, 0.5))
        put("ms_p95", FacesDevice.percentile(times, 0.95))
        put("byte", bytes.size)
      }
    }
  }

  // --- Foto al secondo: da dove nasce DeviceMeasures.facesPerSecond ----------------------------------------------------------------

  /**
   * Il giro di una foto come lo fara' il calcolo (P4): decodifica dell'anteprima JPEG, YuNet a 1024, e per ogni volto allineamento, qualita' e SFace. Le foto sono
   * disegnate (nessun volto: YuNet non ne trova) e i volti sono quelli di `aligned()`, in media 1,2 a foto come dice D10 (1, 1, 2, 1, 1, ...): il costo dei
   * modelli e' lo stesso. Tre giri da 40 foto di fila: il secondo e il terzo dicono quanto cala col caldo.
   */
  @Test
  fun fotoAlSecondoEFacesPerSecond() = runBlocking<Unit> {
    val engines = FacesDevice.engines()
    val bitmap = scene(1024, 768)
    val jpegBytes = jpeg(bitmap, 90)
    bitmap.recycle()
    val faces = (0 until 6).map(::aligned)
    val landmarks = faceLandmarks(300f, 200f, 300f, 360f)
    try {
      repeat(8) { engines.detector.detect(checkNotNull(ModelInputs.forFaces(jpegBytes, FaceEngineLimits.DETECT_SIDE)), DetectPass.PREVIEW) }
      val perRound = ArrayList<Double>()
      val thermal = ArrayList<Int>()
      var embedMs = 0.0
      var detectMs = 0.0
      var decodeMs = 0.0
      var alignMs = 0.0
      var photos = 0
      var facesDone = 0
      for (round in 0 until 3) {
        val roundStart = System.nanoTime()
        for (i in 0 until 40) {
          val a = System.nanoTime()
          val image = checkNotNull(ModelInputs.forFaces(jpegBytes, FaceEngineLimits.DETECT_SIDE))
          val b = System.nanoTime()
          engines.detector.detect(image, DetectPass.PREVIEW)
          val c = System.nanoTime()
          val count = if (i % 5 == 2) 2 else 1 // 1,2 volti a foto: 1, 1, 2, 1, 1
          var align = 0L
          val crops = ArrayList<AlignedFace>(count)
          for (k in 0 until count) {
            val t = System.nanoTime()
            val crop = engines.aligner.align(image, landmarks)
            FaceQuality.sharpnessOf(crop)
            align += System.nanoTime() - t
            crops += faces[(i + k) % 6]
          }
          val d = System.nanoTime()
          for (crop in crops) engines.embedder.embed(listOf(crop))
          val e = System.nanoTime()
          decodeMs += FacesDevice.ms(b - a)
          detectMs += FacesDevice.ms(c - b)
          alignMs += FacesDevice.ms(align)
          embedMs += FacesDevice.ms(e - d)
          photos++
          facesDone += count
        }
        perRound += 40 / ((System.nanoTime() - roundStart) / 1e9)
        thermal += thermalStatus()
      }
      val perPhotoMs = (decodeMs + detectMs + alignMs + embedMs) / photos
      FacesDevice.record("foto_al_secondo") {
        put("foto_al_secondo_per_giro", org.json.JSONArray(perRound))
        put("stato_termico_dopo_ogni_giro", org.json.JSONArray(thermal))
        put("ms_per_foto_media", perPhotoMs)
        put("decodifica_ms_per_foto", decodeMs / photos)
        put("yunet_ms_per_foto", detectMs / photos)
        put("allineamento_e_qualita_ms_per_foto", alignMs / photos)
        put("sface_ms_per_foto", embedMs / photos)
        put("volti_per_foto", facesDone.toDouble() / photos)
        put("facesPerSecond_suggerito", 1000.0 / perPhotoMs)
        put("pss_in_lavoro_mb", FacesDevice.pssMb())
      }
      assertTrue(perRound.all { it > 0.0 })
    } finally {
      engines.release()
    }
  }

  /** Con i volti veri delle composizioni, se ci sono: il giro intero (rileva, allinea, giudica, calcola il vettore) per immagine. */
  @Test
  fun foto_al_secondo_con_le_composizioni_di_p1() = runBlocking<Unit> {
    val refs = FacesDevice.requireReferences()
    val items = refs.images.filter { it.detections.isNotEmpty() }.take(20)
    org.junit.Assume.assumeTrue("servono le composizioni in ${FacesDevice.FIXTURES_DIR}/img", items.size >= 5)
    val images = items.map { FacesDevice.loadFixture(it.name) }
    val engines = FacesDevice.engines()
    try {
      repeat(2) { engines.detector.detect(images[it], DetectPass.PREVIEW) }
      var faces = 0
      val t0 = System.nanoTime()
      var rounds = 0
      repeat(3) {
        for (image in images) {
          val found = engines.detector.detect(image, DetectPass.PREVIEW)
          for (f in found) {
            val crop = engines.aligner.align(image, f.landmarks)
            val verdict = engines.quality.assess(f, f.box.shortSide.toInt(), crop)
            if (verdict.tier.hasVector) engines.embedder.embed(listOf(crop))
            faces++
          }
          rounds++
        }
      }
      val seconds = (System.nanoTime() - t0) / 1e9
      FacesDevice.record("foto_al_secondo_composizioni") {
        put("immagini", rounds)
        put("volti", faces)
        put("volti_per_immagine", faces.toDouble() / rounds)
        put("immagini_al_secondo", rounds / seconds)
        put("volti_al_secondo", faces / seconds)
      }
    } finally {
      engines.release()
    }
  }
}
