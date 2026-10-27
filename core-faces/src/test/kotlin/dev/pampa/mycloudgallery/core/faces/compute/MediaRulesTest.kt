package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.testing.SyntheticFace
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cosa si analizza e come (D17-D19, §2.3 righe "Filtro", "Prima passata", "Seconda passata", "Qualita' e vettori", "Video"; E-F13-E-F17):
 * screenshot, documenti e ritagli si saltano; i nascosti si calcolano; la seconda passata sull'HQ si fa **solo quando serve**; dei video solo i
 * volti WEAK; i livelli dalla qualita'; i vettori validati; gli errori del motore come codici (IX-7).
 */
class MediaRulesTest {
  private val forever = SimWorld.FOREVER

  private class Case(val w: SimWorld = SimWorld()) {
    val phone = w.device(w.anna, "telefono-anna")
    val priv get() = w.anna.privateShare

    suspend fun run() = phone.engine.run(phone.area(AreaKind.PRIVATE), SimWorld.FOREVER)

    suspend fun body(key: ContentKey): FaceBody = phone.rawArchive.winners(listOf(key)).getValue(key)
  }

  // ---- Filtro (D18) -----------------------------------------------------------------------------------------------------------------

  @Test
  fun screenshotDocumentiRitagliEImmaginiAnimateSiSaltano() = runTest {
    val c = Case()
    val normal = c.w.nas.add(c.priv, faces = listOf(Faces.big(1)))
    c.w.nas.add(c.priv, source = MediaSource.SCREENSHOT)
    c.w.nas.add(c.priv, document = true)
    c.w.nas.add(c.priv, cutout = true)
    c.w.nas.add(c.priv, kind = MediaKind.ANIMATED_IMAGE)

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(1)
    assertThat(r.skipped).isEqualTo(4)
    assertThat(c.phone.inputs.previewed).containsExactly(normal)
    assertThat(c.phone.analyzed()).containsExactly(normal)
    assertThat(c.phone.ticking.detects).isEqualTo(1)
    assertThat(c.phone.engine.counters.excluded.get()).isEqualTo(4)
  }

  /** Un mese di soli screenshot non e' un'unita' e non apre niente: nessuna prenotazione, nessun ingresso. */
  @Test
  fun unMeseDiSoliScreenshotNonTocca() = runTest {
    val c = Case()
    repeat(3) { c.w.nas.add(c.priv, source = MediaSource.SCREENSHOT) }
    val r = c.run()
    assertThat(r.analyzed + r.skipped).isEqualTo(0)
    assertThat(c.phone.leases.calls).isEqualTo(0)
    assertThat(c.phone.inputs.previewCalls).isEqualTo(0)
  }

  @Test
  fun iNascostiSiCalcolano() = runTest {
    val c = Case()
    val hidden = c.w.nas.add(c.priv, faces = listOf(Faces.big(1)), hidden = true)
    assertThat(c.run().analyzed).isEqualTo(1)
    assertThat(c.phone.analyzed()).containsExactly(hidden)
    assertThat(c.w.published(c.w.anna, "telefono-anna")).containsExactly(hidden)
  }

  // ---- Seconda passata (D17) ---------------------------------------------------------------------------------------------------------

  @Test
  fun secondaPassata_nonSeNonServe() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1), Faces.big(2, x = 0.55f)))
    c.run()
    assertThat(c.phone.inputs.hqCalls).isEqualTo(0) // l'HQ nemmeno si chiede
    assertThat(c.phone.ticking.passes).containsExactly(DetectPass.PREVIEW)
    assertThat(c.body(key).source).isEqualTo(FaceSource.ANALYSIS)
    assertThat(c.body(key).faces.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.CORE)
  }

  @Test
  fun secondaPassata_conUnVoltoSotto40px() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.small(1)))
    c.run()
    assertThat(c.phone.inputs.hqCalls).isEqualTo(1)
    assertThat(c.phone.ticking.passes).containsExactly(DetectPass.PREVIEW, DetectPass.HQ_TILES).inOrder()
    val body = c.body(key)
    assertThat(body.source).isEqualTo(FaceSource.HQ_TILES)
    // Sull'HQ il volto ha ~61 px: CORE; sull'anteprima (31 px) sarebbe stato WEAK. Il lato corto e' quello della sorgente dell'allineamento.
    assertThat(body.faces.single().tier).isEqualTo(FaceTier.CORE)
    assertThat(body.faces.single().sidePx).isIn(60..63)
    assertThat(c.phone.engine.counters.hqPasses.get()).isEqualTo(1)
  }

  @Test
  fun secondaPassata_conAlmenoCinqueVolti() = runTest {
    val c = Case()
    val four = c.w.nas.add(c.priv, faces = Faces.group(4), month = "2024-02")
    val five = c.w.nas.add(c.priv, faces = Faces.group(5), month = "2024-03")
    c.run()
    assertThat(c.phone.inputs.hqCalls).isEqualTo(1) // solo la foto da cinque volti
    assertThat(c.body(five).source).isEqualTo(FaceSource.HQ_TILES)
    assertThat(c.body(four).source).isEqualTo(FaceSource.ANALYSIS)
  }

  /** HQ assente e non scaricabile: si tengono i volti della prima passata; il piccolo esce WEAK dalla qualita' (E-F13), non si ritenta. */
  @Test
  fun secondaPassata_senzaHqSiTengonoIVoltiDellaPrimaPassata() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1), Faces.small(2, x = 0.6f)), hq = false)
    val r = c.run()
    assertThat(r.analyzed).isEqualTo(1)
    assertThat(c.phone.engine.counters.hqMissing.get()).isEqualTo(1)
    val body = c.body(key)
    assertThat(body.source).isEqualTo(FaceSource.ANALYSIS)
    assertThat(body.faces.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.WEAK)
    assertThat(body.faces[1].reasons and FaceReasons.SMALL).isNotEqualTo(0)
  }

  // ---- Livelli (D19) -------------------------------------------------------------------------------------------------------------------

  @Test
  fun livelliCoreWeakNone_soloCoreEWeakHannoIlVettore() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1), Faces.profile(2, x = 0.4f), Faces.tiny(3, x = 0.8f)), hq = false)
    c.run()
    val faces = c.body(key).faces
    assertThat(faces.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.WEAK, FaceTier.NONE)
    assertThat(faces[0].vector).isNotNull()
    assertThat(faces[1].vector).isNotNull()
    assertThat(faces[1].reasons and FaceReasons.PROFILE).isNotEqualTo(0)
    assertThat(faces[2].vector).isNull() // sotto 24 px: "1 volto non riconoscibile", nessun vettore
    assertThat(c.phone.rawArchive.unrecognizable(listOf(key))).containsExactly(key, 1)
  }

  @Test
  fun vettoriInFloat16UnitariENessunAttributo() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1), Faces.big(2, x = 0.55f)))
    c.run()
    for (face in c.body(key).faces) {
      val v = Float16.decode(face.vector!!)
      assertThat(v).hasLength(128)
      assertThat(abs(sqrt(v.fold(0.0) { a, x -> a + x * x }) - 1.0)).isLessThan(0.01)
    }
  }

  @Test
  fun riquadroEPuntiInFrazioniDellImmagine() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1, x = 0.1f)))
    c.run()
    val face = c.body(key).faces.single()
    assertThat(face.box.x0).isIn((0.1f * 65535 - 2).toInt()..(0.1f * 65535 + 2).toInt())
    assertThat(face.box.x1).isIn((0.35f * 65535 - 2).toInt()..(0.35f * 65535 + 2).toInt())
    val xs = (0 until 5).map { face.landmarks[it * 2].toInt() and 0xFFFF }
    assertThat(xs.all { it in face.box.x0..face.box.x1 }).isTrue()
    assertThat(face.sidePx).isIn(255..257) // 256 px a 1024
    assertThat(face.score).isEqualTo(Math.round(0.95f * 255))
  }

  /** Un vettore non valido (valori non finiti, norma lontana da 1) lascia solo quel volto senza vettore: il resto del media si salva. */
  @Test
  fun vettoreNonValido_ilVoltoRestaSenzaVettore() = runTest {
    val c = Case()
    val scene = c.w.nas.makeScene(listOf(Faces.big(1), Faces.big(2, x = 0.55f)))
    val key = c.w.nas.add(c.priv, scene = scene)
    c.phone.fake.invalidVectors += Triple(scene.id, 0, 1)

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(1)
    val faces = c.body(key).faces
    assertThat(faces.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.NONE)
    assertThat(faces[1].vector).isNull()
    assertThat(c.phone.engine.counters.vectorsInvalid.get()).isEqualTo(1)
    assertThat(c.phone.signals).contains(FacesDiag.FACES_VECTOR_INVALID to 1)
  }

  // ---- Video (D18, E-F17) ------------------------------------------------------------------------------------------------------------------

  @Test
  fun video_copertinaEFotogrammi_soloWeakEMaiLHq() = runTest {
    val c = Case()
    val faces = (0..3).map { f -> Faces.big(f + 1, frame = f) }
    val key = c.w.nas.add(c.priv, faces = faces, kind = MediaKind.VIDEO)
    c.phone.inputs.extraFrames = 3 // la porta ne restituisce 7: il calcolo ne prende al piu' 4 (copertina + 3)

    val r = c.run()

    assertThat(r.analyzed).isEqualTo(1)
    val body = c.body(key)
    assertThat(body.source).isEqualTo(FaceSource.FRAMES)
    assertThat(body.faces).hasSize(4)
    assertThat(body.faces.map { it.frame }).containsExactly(0, 1, 2, 3).inOrder()
    for (face in body.faces) {
      assertThat(face.tier).isEqualTo(FaceTier.WEAK) // mai CORE: non fanno nascere persone da soli
      assertThat(face.reasons and FaceReasons.FRAME).isNotEqualTo(0)
      assertThat(face.vector).isNotNull()
    }
    assertThat(c.phone.ticking.detects).isEqualTo(4)
    assertThat(c.phone.inputs.hqCalls).isEqualTo(0)
    assertThat(c.phone.inputs.previewCalls).isEqualTo(0)
    assertThat(c.phone.engine.counters.videoFrames.get()).isEqualTo(4)
  }

  @Test
  fun video_senzaFotogrammiAspetta() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, kind = MediaKind.VIDEO, previewOnNas = false)
    val r = c.run()
    assertThat(r.analyzed).isEqualTo(0)
    assertThat(r.waiting).isEqualTo(1)
    assertThat(c.phone.analyzed()).doesNotContain(key)
    assertThat(c.phone.signals).contains(FacesDiag.FACES_WAITING_PREVIEWS to 1)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.PREVIEWS))
  }

  /** Un corpo ha al piu' 64 volti (§2.4): di un video con piu' volti si tengono i meglio rilevati. */
  @Test
  fun corpoDiAlPiu64Volti() = runTest {
    val c = Case()
    val faces = (0..3).flatMap { f ->
      (0 until 4).flatMap { r ->
        (0 until 5).map { col ->
          val x0 = 0.02f + 0.19f * col
          val y0 = 0.05f + 0.23f * r
          SyntheticFace(col + 1, x0, y0, x0 + 0.15f, y0 + 0.2f, frame = f, score = 0.7f + 0.01f * (col + r))
        }
      }
    }
    val key = c.w.nas.add(c.priv, faces = faces, kind = MediaKind.VIDEO)
    c.run()
    val body = c.body(key)
    assertThat(body.faces).hasSize(64)
    assertThat(body.faces.map { it.index }).containsExactlyElementsIn(0 until 64).inOrder()
  }

  // ---- Attese, rifiuti, sorgente cambiata -------------------------------------------------------------------------------------------------

  @Test
  fun anteprimaAssente_ilMediaAspetta_poiArrivaESiCalcola() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1)), previewOnNas = false)
    val first = c.run()
    assertThat(first.waiting).isEqualTo(1)
    assertThat(first.analyzed).isEqualTo(0)
    assertThat(c.phone.units.marks).isEqualTo(0) // l'unita' non e' finita
    assertThat(c.phone.ticking.detects).isEqualTo(0)

    c.phone.buffer += key // arriva dal backup
    val second = c.run()
    assertThat(second.analyzed).isEqualTo(1)
    assertThat(second.waiting).isEqualTo(0)
    assertThat(c.phone.units.marks).isEqualTo(1)
  }

  @Test
  fun formatoNonDecodificabile_nonSupportato_nonSiRiprova() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv)
    c.phone.inputs.scripted[key] = FaceInput.Unsupported
    val first = c.run()
    assertThat(first.unsupported).isEqualTo(1)
    assertThat(c.phone.archive.unsupported).containsExactly(key)
    assertThat(c.phone.analyzed()).containsExactly(key) // "non supportato" conta come fatto
    val calls = c.phone.inputs.previewCalls

    c.run()
    assertThat(c.phone.inputs.previewCalls).isEqualTo(calls) // non si riprova finche' la ricetta non cambia
  }

  @Test
  fun sorgenteCambiata_ilMediaSiSalta() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv)
    c.phone.inputs.scripted[key] = FaceInput.Changed
    val r = c.run()
    assertThat(r.analyzed).isEqualTo(0)
    assertThat(r.skipped).isEqualTo(1)
    assertThat(c.phone.analyzed()).doesNotContain(key)
    c.phone.inputs.scripted.clear()
    assertThat(c.run().analyzed).isEqualTo(1) // il catalogo si aggiorna: il media torna buono
  }

  // ---- Errori del motore (IX-7) ---------------------------------------------------------------------------------------------------------

  @Test
  fun ingressoRifiutatoDalMotore_nonSupportato() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv)
    c.phone.ticking.failDetect += AiEngineError.BAD_INPUT
    val r = c.run()
    assertThat(r.unsupported).isEqualTo(1)
    assertThat(c.phone.analyzed()).containsExactly(key)
  }

  /** Un guasto passeggero (memoria) non e' un rifiuto: il media si riprova; al terzo errore nello stesso processo vale come non valido. */
  @Test
  fun erroreDelMotorePasseggero_siRiprova_alTerzoNonSupportato() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv)
    c.phone.ticking.failDetect += AiEngineError.OUT_OF_MEMORY
    val first = c.run()
    assertThat(first.analyzed).isEqualTo(0)
    assertThat(first.unsupported).isEqualTo(0)
    assertThat(c.phone.engine.counters.engineFailures.get()).isEqualTo(1)
    assertThat(c.phone.analyzed()).isEmpty()

    assertThat(c.run().analyzed).isEqualTo(1) // alla tappa dopo va

    val d = Case()
    val bad = d.w.nas.add(d.priv)
    repeat(3) { d.phone.ticking.failDetect += AiEngineError.OUT_OF_MEMORY }
    repeat(2) { assertThat(d.run().unsupported).isEqualTo(0) }
    assertThat(d.run().unsupported).isEqualTo(1)
    assertThat(d.phone.analyzed()).containsExactly(bad)
  }

  @Test
  fun dueErroriDelMotoreDiFila_tappaFermaConFacesEngineFailed() = runTest {
    val c = Case()
    repeat(4) { c.w.nas.add(c.priv, faces = listOf(Faces.big(it + 1))) }
    repeat(2) { c.phone.ticking.failDetect += AiEngineError.OUT_OF_MEMORY }
    val r = c.run()
    assertThat(r.stop).isEqualTo(FacesStop.ENGINE_FAILED)
    assertThat(r.analyzed).isEqualTo(0)
    assertThat(c.phone.signals).contains(FacesDiag.FACES_ENGINE_FAILED to 1)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Problem(FacesDiag.FACES_ENGINE_FAILED))
  }

  @Test
  fun unSuccessoInterrompeLaSerieDegliErrori() = runTest {
    val c = Case()
    repeat(4) { c.w.nas.add(c.priv, faces = listOf(Faces.big(it + 1))) }
    c.phone.ticking.failDetect += AiEngineError.OUT_OF_MEMORY
    // Il primo fallisce, i successivi vanno: nessuna serie di due.
    val r = c.run()
    assertThat(r.stop).isEqualTo(FacesStop.DONE)
    assertThat(r.analyzed).isEqualTo(3)
  }

  @Test
  fun ilMotoreNonParte_immediatamente() = runTest {
    val c = Case()
    c.w.nas.add(c.priv)
    c.phone.ticking.failDetect += AiEngineError.INIT_FAILED
    assertThat(c.run().stop).isEqualTo(FacesStop.ENGINE_FAILED)

    val d = Case()
    d.w.nas.add(d.priv)
    d.phone.enginesAvailable = false
    val r = d.run()
    assertThat(r.stop).isEqualTo(FacesStop.ENGINE_FAILED)
    assertThat(d.phone.ticking.detects).isEqualTo(0)
  }

  @Test
  fun modelloMancante_tappaFermaSenzaAprireNiente() = runTest {
    val c = Case()
    c.w.nas.add(c.priv)
    c.phone.modelsReady = false
    val r = c.run()
    assertThat(r.stop).isEqualTo(FacesStop.MODELS_UNAVAILABLE)
    assertThat(c.phone.opens).isEqualTo(0)
    assertThat(c.phone.signals).contains(FacesDiag.FACES_MODEL_MISMATCH to 1)
    assertThat(c.phone.engine.status.value).isEqualTo(FacesStatus.Waiting(FacesWait.MODELS))

    val d = Case()
    d.w.nas.add(d.priv)
    d.phone.ticking.failDetect += AiEngineError.MODEL_UNAVAILABLE
    assertThat(d.run().stop).isEqualTo(FacesStop.MODELS_UNAVAILABLE)
  }

  // ---- Il corpo e la sua impronta (§2.4) ------------------------------------------------------------------------------------------------

  @Test
  fun corpoCodificatoComeIlFormatoEImprontaStabile() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1), Faces.tiny(2, x = 0.8f)), hq = false)
    c.run()
    val body = c.body(key)
    val draft = FaceBodyDraft(body.contentKey, body.space, body.detector, body.recipe, body.source, body.faces)
    val bytes = FaceBodyEncoding.encode(draft)

    // 6 byte di testa + (35 + 256) per il volto CORE + 35 per il NONE, senza vettore.
    assertThat(bytes.size).isEqualTo(6 + FaceBodyEncoding.FACE_BYTES_WITH_VECTOR + FaceBodyEncoding.FACE_BYTES_WITHOUT_VECTOR)
    assertThat(bytes.take(6).map { it.toInt() }).containsExactly(1, body.space.wire, body.detector.wire, body.recipe, body.source.wire, 2).inOrder()
    // Il byte "livello e motivi" del secondo volto: NONE (0) con il motivo "piccolo" sopra i due bit del livello.
    val second = 6 + FaceBodyEncoding.FACE_BYTES_WITH_VECTOR
    assertThat(bytes[second + 1 + 8 + 20 + 1].toInt() and 0x03).isEqualTo(FaceTier.NONE.wire)
    assertThat(bytes[second + 1 + 8 + 20 + 1].toInt() shr 2).isEqualTo(body.faces[1].reasons)
    // L'impronta e' quella del corpo: la stessa con gli stessi dati, un'altra se cambia un solo mezzo-float.
    assertThat(body.digest).isEqualTo(Sha256FaceBodyDigest.digestOf(draft))
    assertThat(Sha256FaceBodyDigest.digestOf(draft)).isEqualTo(Sha256FaceBodyDigest.digestOf(draft))
    val v = body.faces[0].vector!!.also { it[5] = (it[5] + 1).toShort() }
    val changed = FaceBodyDraft(body.contentKey, body.space, body.detector, body.recipe, body.source, listOf(rebuilt(body.faces[0], v), body.faces[1]))
    assertThat(Sha256FaceBodyDigest.digestOf(changed)).isNotEqualTo(body.digest)
    assertThat(body.author).isEqualTo("telefono-anna")
    assertThat(body.recipe).isEqualTo(1)
  }

  private fun rebuilt(face: dev.pampa.mycloudgallery.core.faces.contract.FaceEntry, vector: ShortArray) =
    dev.pampa.mycloudgallery.core.faces.contract.FaceEntry(
      face.index, face.frame, face.box, face.landmarks, face.score, face.tier, face.reasons, face.sidePx, face.yaw, face.sharpness, vector,
    )

  /** D37: nei contatori, nei segnali e nello stato passano solo numeri e codici, mai una chiave, un riquadro, un vettore. */
  @Test
  fun nienteDiPersonaleNeiResoconti() = runTest {
    val c = Case()
    val key = c.w.nas.add(c.priv, faces = listOf(Faces.big(1)))
    val r = c.run()
    val text = listOf(r.toString(), c.phone.engine.status.value.toString(), c.phone.signals.toString(), c.body(key).toString(), c.body(key).faces.toString())
    for (line in text) {
      assertThat(line).doesNotContain(key.hex)
      assertThat(line).doesNotContain(c.w.anna.generation.base32)
    }
  }
}
