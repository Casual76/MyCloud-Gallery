package dev.pampa.mycloudgallery.core.nasops.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Fase 8, P8 (D31, §2.8 invariante 5, "Fatto quando": mai un percorso fuori da `faces/`, mai un media, una voce falsa tocca solo dati dei volti)
 * sul [NasFacesEraser] vero e sul simulatore SMB: file strani, collegamenti (punti di rianalisi e collegamenti "trasparenti" di Samba),
 * collegamenti fisici a un media, alberi a caso con media, guasti a ogni richiesta, file aperti o cambiati. Ogni prova controlla, oltre agli
 * esiti, **ogni richiesta** che puo' cambiare un file ([FacesSmbWorld.changes]) e i **byte** di ogni media prima e dopo.
 */
class NasFacesEraserSafetyTest {

  private val worlds = mutableListOf<FacesSmbWorld>()

  @After
  fun close() = worlds.forEach { it.close() }

  private fun world(maxFilesPerRound: Int = 20_000) = FacesSmbWorld(maxFilesPerRound = maxFilesPerRound).also { worlds += it }

  private val share = "test"
  private val g1 = FaceGeneration.fromBytes(ByteArray(16) { 1 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 2 })
  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val budget = 5.minutes

  private fun hash(text: String) = Hashes.sha256Name(text.toByteArray())

  /** I dati dei volti di [g] per [devices] (teste, segmenti, prenotazione del custode). */
  private fun FacesSmbWorld.faces(g: FaceGeneration, vararg devices: String): List<SharePath> {
    val out = ArrayList<SharePath>()
    for (d in devices) {
      for (v in 1L..2L) out += NasLayout.facesHead(g, d, v).also { put(share, it) }
      for (n in 0 until 3) out += NasLayout.facesSegment(g, d, hash("${g.base32}$d$n")).also { put(share, it) }
    }
    out += NasLayout.facesLeaseFile(g, NasLayout.facesClusterResource(g), 1).also { put(share, it) }
    return out
  }

  /** I media della share e i loro byte (per il confronto "prima e dopo"). */
  private fun FacesSmbWorld.media(vararg paths: String): Map<String, ByteArray> = paths.associateWith { p ->
    val bytes = "media $p".toByteArray()
    media += put(share, SharePath.parse(p), bytes)
    bytes
  }

  private fun FacesSmbWorld.mediaIntact(media: Map<String, ByteArray>) {
    for ((path, bytes) in media) {
      assertWithMessage("il media $path e' cambiato o sparito").that(backend.bytes(share, path)).isEqualTo(bytes)
    }
  }

  /** Ogni richiesta che puo' cambiare un file e' sotto `faces/` dell'area privata, nelle cartelle ammesse da [allowed]. */
  private fun FacesSmbWorld.onlyFaces(allowed: (SharePath) -> Boolean) {
    for ((op, raw) in changes) {
      val path = SharePath.parse(raw)
      assertWithMessage("$op fuori da faces/").that(path.isWithin(NasLayout.FACES_DIR) && path != NasLayout.FACES_DIR).isTrue()
      assertWithMessage("$op su un posto non ammesso").that(allowed(path)).isTrue()
    }
  }

  @Test
  fun iFileStraniSottoLaGenerazioneNonSiToccano() = runTest {
    val w = world()
    val faces = w.faces(g1, me, other)
    val junk = listOf(
      NasLayout.facesGenerationDir(g1).child("leggimi.txt"),
      NasLayout.facesGenerationDir(g1).child("altro").child("file"),
      NasLayout.facesDeviceDir(g1, me).child("note").child("0000000001"),
      NasLayout.facesHeadDir(g1, me).child("non-un-numero"),
      NasLayout.facesSegmentDir(g1, other).child("IMG_0001.jpg"),
      NasLayout.facesGenerationDir(g1).child("leases").child("cartella-strana").child("1"),
    )
    junk.forEach { w.put(share, it) }
    val report = w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(report.stoppedBy).isEqualTo(FacesEraseStop.DONE)
    assertThat(report.removedFiles).isEqualTo(faces.size)
    assertThat(w.files(share)).containsExactlyElementsIn(junk)
    assertThat(w.eraser.verify(share, g1)).isEqualTo(FacesEraseCheck.Checked(generationPresent = false, recycleBinCopy = false))
    w.onlyFaces { it.isWithin(NasLayout.facesGenerationDir(g1)) && it !in junk }
  }

  @Test
  fun unCollegamentoAUnaCartellaDiMediaNonPortaMaiLaCancellazioneSuiMedia() = runTest {
    for (transparent in listOf(false, true)) {
      val w = world()
      val media = w.media("Foto/head/0000000001", "Foto/head/0000000002", "Foto/seg/${hash("x")}", "Foto/2025/IMG_0001.jpg")
      w.faces(g1, me)
      // Collegamenti messi da chi ha la password, con i nomi giusti, verso le cartelle dei media: al posto di un dispositivo, di `head`, di
      // `seg`. Un punto di rianalisi non si attraversa; un collegamento "trasparente" (Samba di serie) si elenca, ma i file che si vedono
      // dentro hanno l'identita' dei media, che il catalogo conosce: non si aprono nemmeno.
      val third = TrustIds.random()
      w.backend.putLink(share, NasLayout.facesDeviceDir(g1, other).value, "Foto", transparent = transparent)
      w.backend.putLink(share, NasLayout.facesHeadDir(g1, third).value, "Foto/head", transparent = transparent)
      w.backend.putLink(share, NasLayout.facesSegmentDir(g1, third).value, "Foto/seg", transparent = transparent)
      var rounds = 0
      while (w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy != FacesEraseStop.DONE) {
        assertThat(++rounds).isLessThan(5)
      }
      w.mediaIntact(media)
      w.onlyFaces { !it.isWithin(NasLayout.facesDeviceDir(g1, other)) && !it.isWithin(NasLayout.facesDeviceDir(g1, third)) }
      assertThat(w.files(share).filter { it.isWithin(NasLayout.facesGenerationDir(g1)) }).isEmpty()
      assertThat((w.eraser.verify(share, g1) as FacesEraseCheck.Checked).generationPresent).isFalse()
    }
  }

  @Test
  fun unCollegamentoFisicoAUnMediaNonSiTocca() = runTest {
    val w = world()
    val media = w.media("Foto/2025/IMG_0002.jpg")
    w.faces(g1, me)
    val link = NasLayout.facesSegment(g1, me, hash("collegamento"))
    w.backend.putHardLink(share, "Foto/2025/IMG_0002.jpg", link.value)
    assertThat(w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.DONE)
    // Il catalogo conosce l'identita' del media: il nome sotto faces/ non si apre nemmeno.
    assertThat(w.changes.map { it.second }).doesNotContain(link.value)
    assertThat(w.backend.links(share, "Foto/2025/IMG_0002.jpg")).isEqualTo(2)
    w.mediaIntact(media)
  }

  @Test
  fun laGenerazioneCorrenteEUnDispositivoNonRevocatoNonSiToccanoMai() = runTest {
    val w = world()
    w.faces(g1, me, other)
    w.faces(g2, me, other)
    w.facts.current = FacesCurrent.Known(g1)
    assertThat(w.eraser.eraseGeneration(share, g1, FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    assertThat(w.eraser.eraseDevice(share, g1, other, FacesEraseReason.DEVICE_REVOKED, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    assertThat(w.changes).isEmpty()
    w.facts.revoked += other
    assertThat(w.eraser.eraseDevice(share, g1, other, FacesEraseReason.DEVICE_REVOKED, budget).stoppedBy).isEqualTo(FacesEraseStop.DONE)
    // Della generazione corrente solo la cartella del revocato; le prenotazioni (della generazione, non del dispositivo) restano.
    w.onlyFaces { it.isWithin(NasLayout.facesDeviceDir(g1, other)) }
    assertThat(w.files(share).count { it.isWithin(NasLayout.facesGenerationDir(g1)) }).isEqualTo(5 + 1)
    assertThat(w.files(share).count { it.isWithin(NasLayout.facesGenerationDir(g2)) }).isEqualTo(11)
  }

  @Test
  fun unFileApertoOCambiatoRestaPerIlGiroDopo() = runTest {
    val w = world()
    val faces = w.faces(g1, me)
    w.backend.lock(share, faces.first().value)
    val first = w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(first.stoppedBy).isEqualTo(FacesEraseStop.BUDGET)
    assertThat(w.files(share)).containsExactly(faces.first())
    w.backend.unlock(share, faces.first().value)
    // Cambiato fra l'elenco e la cancellazione: il timbro non torna, resta.
    val target = faces.first().value
    var changed = false
    val hook = w.backend.beforeOp
    w.backend.beforeOp = { op, path ->
      hook?.invoke(op, path)
      if (op == SimOp.OPEN_CHANGE && path.replace('\\', '/') == target && !changed) {
        changed = true
        w.backend.put(share, target, ByteArray(40) { 7 })
      }
    }
    assertThat(w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.BUDGET)
    assertThat(w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.DONE)
    assertThat(w.files(share)).isEmpty()
  }

  @Test
  fun elencoDelleGenerazioniSenzaCollegamentiNeFile() = runTest {
    val w = world()
    w.faces(g1, me)
    w.put(share, NasLayout.FACES_DIR.child(g2.base32))
    w.backend.putLink(share, NasLayout.FACES_DIR.child(FaceGeneration.fromBytes(ByteArray(16) { 3 }).base32).value, "Foto")
    w.put(share, NasLayout.FACES_DIR.child("strano").child("x"))
    val listed = w.eraser.listGenerations(share) as FacesGenerations.Listed
    assertThat(listed.generations).containsExactly(g1)
    assertThat(listed.ignoredNames).isEqualTo(3)
  }

  @Test
  fun ilCestinoDiReteSiRiconosceConTuttiINomi() = runTest {
    for (bin in listOf(".recycle", ".Recycle Bin", "\$RECYCLE.BIN")) {
      val w = world()
      w.faces(g1, me)
      w.recycleBin = bin
      w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget)
      assertThat(w.eraser.verify(share, g1)).isEqualTo(FacesEraseCheck.Checked(generationPresent = false, recycleBinCopy = true))
    }
  }

  /** "FaultPlan a ogni chiamata": un guasto prima e dopo ogni richiesta al server; i giri dopo arrivano in fondo, senza toccare altro. */
  @Test
  fun unGuastoAOgniRichiestaPrimaEDopo() = runTest {
    val clean = world()
    clean.faces(g1, me, other)
    clean.faces(g2, me)
    val start = clean.faults.operations
    clean.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget)
    val calls = clean.faults.operations - start
    assertThat(calls).isGreaterThan(10)
    var fired = 0
    for (n in 1..calls) {
      for (phase in FaultPhase.entries) {
        for (fault in listOf(SimFault.DropConnection, SimFault.Status(FacesSmbWorld.STATUS_UNSUCCESSFUL))) {
          val w = world()
          val keep = w.faces(g2, me)
          val media = w.media("Foto/2025/IMG_0003.jpg")
          w.faces(g1, me, other)
          w.faults.at(w.faults.operations + n, phase, fault)
          var rounds = 0
          while (w.eraser.eraseGeneration(share, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy != FacesEraseStop.DONE) {
            assertWithMessage("guasto $fault $phase alla richiesta $n: non arriva in fondo").that(++rounds).isLessThan(6)
            // Il giro dopo arriva piu' tardi (il lavoro si riprogramma): la sessione si riapre.
            w.monotonic.advance(10 * 60_000L)
          }
          fired += w.faults.fired.size
          assertThat(w.files(share).filter { it.isWithin(NasLayout.facesGenerationDir(g1)) }).isEmpty()
          assertThat(w.files(share).filter { it.isWithin(NasLayout.facesGenerationDir(g2)) }).containsExactlyElementsIn(keep)
          w.mediaIntact(media)
          w.onlyFaces { it.isWithin(NasLayout.facesGenerationDir(g1)) }
          w.close()
          worlds.remove(w)
        }
      }
    }
    assertThat(fired).isGreaterThan(calls)
  }

  /** Proprieta': alberi a caso con media, dati dei volti, file strani e collegamenti; fatti, motivi e guasti a caso. Mai un media. */
  @Test
  fun proprietaMaiUnMediaMaiFuoriDallaRegola() = runTest {
    val random = Random(808)
    val generations = listOf(g1, g2, FaceGeneration.fromBytes(ByteArray(16) { 9 }))
    repeat(25) { round ->
      val w = world(maxFilesPerRound = 1 + random.nextInt(12))
      val media = w.media(*Array(4) { "Foto/${round}/IMG_$it.jpg" }, "Foto/head/0000000001")
      generations.forEach { w.faces(it, me, other) }
      // E-F42: dati dei volti nell'area comune (per errore o falsi): mai toccati.
      val inCommon = NasLayout.facesHead(g1, me, 1).also { w.put("test-comune", it) }
      if (random.nextBoolean()) w.backend.putLink(share, NasLayout.facesDeviceDir(g1, TrustIds.random()).value, "Foto", transparent = random.nextBoolean())
      if (random.nextBoolean()) w.put(share, NasLayout.facesGenerationDir(g2).child("strano.bin"))
      val current = listOf(null, g1, g2)[random.nextInt(3)]
      w.facts.current = if (random.nextInt(6) == 0) FacesCurrent.Unknown else FacesCurrent.Known(current)
      if (random.nextBoolean()) w.facts.revoked += other
      repeat(10) {
        if (random.nextInt(5) == 0) w.failNext(1)
        w.blocked = if (random.nextInt(8) == 0) FacesEraseStop.FROZEN else null
        val g = generations[random.nextInt(generations.size)]
        val reason = FacesEraseReason.entries[random.nextInt(FacesEraseReason.entries.size)]
        val target = if (random.nextInt(6) == 0) "test-comune" else share
        if (random.nextBoolean()) {
          w.eraser.eraseGeneration(target, g, reason, budget)
        } else {
          w.eraser.eraseDevice(target, g, if (random.nextBoolean()) me else other, reason, budget)
        }
      }
      w.mediaIntact(media)
      assertThat(w.files("test-comune")).containsExactly(inCommon)
      if (w.facts.current == FacesCurrent.Unknown) assertThat(w.changes).isEmpty()
      val known = (w.facts.current as? FacesCurrent.Known)?.generation
      w.onlyFaces { path ->
        val inCurrent = known != null && path.isWithin(NasLayout.facesGenerationDir(known))
        !inCurrent || path.isWithin(NasLayout.facesDeviceDir(known!!, other)) && other in w.facts.revoked
      }
    }
  }
}
