package dev.pampa.mycloudgallery.core.nasops.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nasops.contract.FacesCurrent
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseCheck
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseReason
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesGenerations
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.testing.FakeFacesEraseFacts
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La SUITE DI CONTRATTO della cancellazione stretta dei volti (docs/fase8-progetto.md §5.2, D31, D35, §2.8 passi 8-9). Gira sull'esecutore
 * di riferimento di P0 ([InMemoryFacesNasEraserContractTest]) e, con lo stesso [World], sul `FacesNasEraser` vero di P8 sopra il simulatore
 * SMB: stessi file tolti, stessi esiti. Ogni prova controlla anche l'invariante 5 del §2.8: **mai** un file tolto fuori da
 * `.mycloudgallery/faces/` dell'area privata, **mai** un media, mai la generazione corrente.
 */
abstract class FacesNasEraserContract {

  /** Il mondo di una prova: il NAS con le sue share, l'eraser sopra, i fatti, il cancello, i guasti e il cestino di rete. */
  interface World {
    val eraser: FacesNasEraser
    val facts: FakeFacesEraseFacts

    fun put(share: String, path: SharePath)

    fun files(share: String): List<SharePath>

    /** I file tolti finora (share e percorso), anche quelli finiti nel cestino di rete. */
    fun removed(): List<Pair<String, SharePath>>

    fun failNext(operations: Int)

    fun block(stop: FacesEraseStop?)

    fun recycleBin(name: String?)
  }

  /** Un mondo nuovo con l'area privata [privateShare], l'area comune [commonShare] e al piu' [maxFilesPerRound] file per giro. */
  protected abstract fun world(privateShare: String, commonShare: String, maxFilesPerRound: Int = 20_000): World

  private val private = "test"
  private val common = "test-comune"
  private val g1 = FaceGeneration.fromBytes(ByteArray(16) { 1 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 2 })
  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val budget = 5.minutes

  /** Un mondo tipico: due generazioni con due dispositivi e le prenotazioni, piu' media, indice, dati personali e un falso nell'area comune. */
  private fun seeded(maxFilesPerRound: Int = 20_000): World {
    val w = world(private, common, maxFilesPerRound)
    for (g in listOf(g1, g2)) {
      for (d in listOf(me, other)) {
        for (v in 1L..3L) w.put(private, NasLayout.facesHead(g, d, v))
        for (n in 0 until 4) w.put(private, NasLayout.facesSegment(g, d, hash(g, d, n)))
      }
      w.put(private, NasLayout.facesLeaseFile(g, NasLayout.facesClusterResource(g), 1))
    }
    w.put(private, SharePath.parse("Foto/2025/IMG_0001.jpg"))
    w.put(private, NasLayout.headFile(me, "personal", 1))
    w.put(private, NasLayout.headFile(me, "index", 1))
    w.put(common, SharePath.parse("Foto/comune.jpg"))
    // E-F42: dati dei volti falsi nell'area comune: mai letti, mai scritti, mai toccati.
    w.put(common, NasLayout.facesHead(g1, me, 1))
    return w
  }

  private fun hash(g: FaceGeneration, d: String, n: Int): String =
    dev.pampa.mycloudgallery.core.trust.crypto.Hashes.sha256Name("${g.base32}/$d/$n".toByteArray())

  /**
   * Invariante 5 del §2.8: ogni file tolto sta sotto `faces/<G>/` dell'area privata; della generazione corrente solo la cartella di un
   * dispositivo revocato o perso (D31 (b)).
   */
  private fun checkOnlyFaces(w: World, current: FaceGeneration?) {
    for ((share, path) in w.removed()) {
      assertWithMessage("tolto un file fuori dall'area privata").that(share).isEqualTo(private)
      assertWithMessage("tolto un file fuori da faces/").that(path.isWithin(NasLayout.FACES_DIR) && path != NasLayout.FACES_DIR).isTrue()
      if (current != null && path.isWithin(NasLayout.facesGenerationDir(current))) {
        val ofRevoked = w.facts.revoked.any { path.isWithin(NasLayout.facesDeviceDir(current, it)) }
        assertWithMessage("tolta la generazione corrente fuori da un dispositivo revocato").that(ofRevoked).isTrue()
      }
    }
  }

  @Test
  fun disattivaECancellaToglieLIntaraGenerazioneENientAltro() = runTest {
    val w = seeded()
    val before = w.files(private)
    val report = w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(report.stoppedBy).isEqualTo(FacesEraseStop.DONE)
    assertThat(report.remainingFiles).isEqualTo(0)
    val gone = before - w.files(private).toSet()
    assertThat(gone.all { it.isWithin(NasLayout.facesGenerationDir(g1)) }).isTrue()
    assertThat(gone).hasSize(2 * 3 + 2 * 4 + 1)
    assertThat(w.files(private).filter { it.isWithin(NasLayout.facesGenerationDir(g2)) }).hasSize(15)
    assertThat(w.files(common)).hasSize(2)
    checkOnlyFaces(w, current = null)
    // Idempotente: rifarlo non toglie niente e dice finito.
    val again = w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(again.removedFiles to again.stoppedBy).isEqualTo(0 to FacesEraseStop.DONE)
  }

  @Test
  fun laGenerazioneCorrenteNonSiTocca_eNelDubbioNiente() = runTest {
    val w = seeded()
    w.facts.current = FacesCurrent.Known(g2)
    for (reason in listOf(FacesEraseReason.USER_CONFIRMED, FacesEraseReason.NOT_CURRENT)) {
      assertThat(w.eraser.eraseGeneration(private, g2, reason, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    }
    assertThat(w.eraser.eraseDevice(private, g2, me, FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    assertThat(w.eraser.eraseGeneration(private, g1, FacesEraseReason.DEVICE_REVOKED, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    w.facts.current = FacesCurrent.Unknown
    assertThat(w.eraser.eraseGeneration(private, g1, FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.UNSURE)
    assertThat(w.eraser.eraseDevice(private, g1, other, FacesEraseReason.DEVICE_REVOKED, budget).stoppedBy).isEqualTo(FacesEraseStop.UNSURE)
    assertThat(w.removed()).isEmpty()
    // Una generazione non corrente si toglie; la corrente resta intera.
    w.facts.current = FacesCurrent.Known(g2)
    assertThat(w.eraser.eraseGeneration(private, g1, FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.DONE)
    checkOnlyFaces(w, current = g2)
    assertThat(w.files(private).count { it.isWithin(NasLayout.facesGenerationDir(g2)) }).isEqualTo(15)
  }

  @Test
  fun maiNellAreaComune_maiSenzaIlCancello() = runTest {
    val w = seeded()
    assertThat(w.eraser.eraseGeneration(common, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_PRIVATE_AREA)
    assertThat(w.eraser.listGenerations(common)).isEqualTo(FacesGenerations.Stopped(FacesEraseStop.NOT_PRIVATE_AREA))
    assertThat(w.eraser.eraseGeneration("altro", g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_PRIVATE_AREA)
    for (stop in listOf(FacesEraseStop.FROZEN, FacesEraseStop.EMERGENCY_STOP, FacesEraseStop.CLOCK_JUMP, FacesEraseStop.READ_ONLY, FacesEraseStop.NOT_TRUSTED, FacesEraseStop.TEST_SHARES_ONLY)) {
      w.block(stop)
      assertThat(w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(stop)
    }
    w.block(null)
    assertThat(w.removed()).isEmpty()
    assertThat(w.files(common)).hasSize(2)
  }

  @Test
  fun laCartellaDiUnDispositivoSoloSeRevocatoOperNonCorrente() = runTest {
    val w = seeded()
    w.facts.current = FacesCurrent.Known(g2)
    assertThat(w.eraser.eraseDevice(private, g2, other, FacesEraseReason.DEVICE_REVOKED, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
    w.facts.revoked += other
    val revoked = w.eraser.eraseDevice(private, g2, other, FacesEraseReason.DEVICE_REVOKED, budget)
    assertThat(revoked.stoppedBy).isEqualTo(FacesEraseStop.DONE)
    assertThat(w.removed().map { it.second }.all { it.isWithin(NasLayout.facesDeviceDir(g2, other)) }).isTrue()
    // La propria cartella di una generazione non corrente (§2.8, gli altri dispositivi, punto 3).
    assertThat(w.eraser.eraseDevice(private, g1, me, FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.DONE)
    assertThat(w.files(private).any { it.isWithin(NasLayout.facesDeviceDir(g1, me)) }).isFalse()
    assertThat(w.files(private).count { it.isWithin(NasLayout.facesDeviceDir(g2, me)) }).isEqualTo(7)
    assertThat(w.eraser.eraseDevice(private, g1, "../Foto", FacesEraseReason.NOT_CURRENT, budget).stoppedBy).isEqualTo(FacesEraseStop.NOT_ALLOWED)
  }

  @Test
  fun aLottiEDopoUnGuastoRiprendeFinoInFondo() = runTest {
    val w = seeded(maxFilesPerRound = 4)
    val first = w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(first.stoppedBy).isEqualTo(FacesEraseStop.BUDGET)
    assertThat(first.removedFiles).isEqualTo(4)
    w.failNext(1)
    assertThat(w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy).isEqualTo(FacesEraseStop.UNREACHABLE)
    var rounds = 0
    while (w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget).stoppedBy != FacesEraseStop.DONE) {
      rounds++
      assertThat(rounds).isLessThan(10)
    }
    assertThat(w.files(private).any { it.isWithin(NasLayout.facesGenerationDir(g1)) }).isFalse()
    checkOnlyFaces(w, current = null)
  }

  @Test
  fun elencoEVerificaConIlCestinoDiRete() = runTest {
    val w = seeded()
    w.put(private, NasLayout.FACES_DIR.child("non-una-generazione"))
    val listed = w.eraser.listGenerations(private) as FacesGenerations.Listed
    assertThat(listed.generations).containsExactly(g1, g2)
    assertThat(listed.ignoredNames).isEqualTo(1)
    w.recycleBin(".recycle")
    w.eraser.eraseGeneration(private, g1, FacesEraseReason.USER_CONFIRMED, budget)
    assertThat(w.eraser.verify(private, g1)).isEqualTo(FacesEraseCheck.Checked(generationPresent = false, recycleBinCopy = true))
    // Un dispositivo che pubblicava prima di saperlo: la cartella ricompare e la verifica lo dice (si toglie di nuovo).
    w.put(private, NasLayout.facesHead(g1, other, 9))
    assertThat((w.eraser.verify(private, g1) as FacesEraseCheck.Checked).generationPresent).isTrue()
    assertThat(w.eraser.verify(common, g1)).isEqualTo(FacesEraseCheck.Stopped(FacesEraseStop.NOT_PRIVATE_AREA))
  }

  /** Proprieta' (invariante 5 del §2.8): con fatti, motivi, share e guasti a caso, mai un file tolto fuori dalle regole. */
  @Test
  fun proprietaMaiFuoriDaFacesMaiLaGenerazioneCorrente() = runTest {
    val random = Random(88)
    repeat(60) {
      val w = seeded(maxFilesPerRound = 1 + random.nextInt(8))
      val current = listOf(null, g1, g2)[random.nextInt(3)]
      w.facts.current = if (random.nextInt(8) == 0) FacesCurrent.Unknown else FacesCurrent.Known(current)
      if (random.nextBoolean()) w.facts.revoked += other
      repeat(12) {
        if (random.nextInt(6) == 0) w.failNext(1)
        val share = if (random.nextInt(5) == 0) common else private
        val g = if (random.nextBoolean()) g1 else g2
        val reason = FacesEraseReason.entries[random.nextInt(FacesEraseReason.entries.size)]
        if (random.nextBoolean()) {
          w.eraser.eraseGeneration(share, g, reason, budget)
        } else {
          w.eraser.eraseDevice(share, g, if (random.nextBoolean()) me else other, reason, budget)
        }
      }
      checkOnlyFaces(w, current = (w.facts.current as? FacesCurrent.Known)?.generation)
      assertThat(w.files(private)).contains(SharePath.parse("Foto/2025/IMG_0001.jpg"))
      assertThat(w.files(common)).hasSize(2)
    }
  }
}
