package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.store.FilePersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.sync.SyncLimits
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §4.3: ogni passo in piu' tappe e' idempotente e si prova con un guasto a OGNI richiesta al NAS: prima o dopo che il NAS l'abbia
 * applicata, e con la morte del processo. In tutti i casi il giro dopo porta tutti i dispositivi allo stesso stato, senza teste
 * doppie ne' voci perse. Piu' i tetti di lettura e i tempi con 100.000 voci.
 */
class PersonalFaultTest {

  @get:Rule
  val folder = TemporaryFolder()

  private class Scenario(val world: PersonalWorld) {
    var phone: PersonalDevice = world.device()
    val tablet: PersonalDevice = world.device()
    val keys = (1..5).map { flagKey(mediaId(it), PersonalFields.FAVORITE) } + flagKey(mediaId(1), PersonalFields.HIDDEN)

    /** Il tablet ha gia' pubblicato; il telefono ha modifiche da pubblicare e una testa del tablet da leggere. */
    suspend fun prepare() {
      tablet.favorite(mediaId(6))
      tablet.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
      for (i in 1..5) phone.favorite(mediaId(i))
      phone.hide(mediaId(1))
    }

    /** Il processo del telefono riparte: stesso registro su disco e stessa chiave, oggetti nuovi. */
    fun restartPhone() {
      phone = world.device(signer = phone.signer, ledger = phone.ledger)
    }
  }

  /** Quante richieste al NAS fa il giro del telefono (legge la testa del tablet e pubblica la sua), senza guasti. */
  private suspend fun cleanCalls(): Int {
    val s = Scenario(PersonalWorld())
    s.prepare()
    val fs = s.world.fs("test")
    fs.armFault(at = Int.MAX_VALUE, after = false, death = false)
    s.phone.syncOnce()
    return fs.faultCalls
  }

  @Test
  fun unGuastoAOgniRichiestaNonLasciaTesteDoppieNeVociPerse() = runTest {
    val calls = cleanCalls()
    // Elenco dei dispositivi, lettura e elenco della testa del tablet, il suo segmento, quella del telefono, segmento e testa propri.
    assertThat(calls).isAtLeast(7)
    var runs = 0
    for (at in 1..calls) {
      for (after in listOf(false, true)) {
        for (death in listOf(false, true)) {
          val s = Scenario(PersonalWorld())
          s.prepare()
          val fs = s.world.fs("test")
          fs.armFault(at, after, death)
          try {
            s.phone.syncOnce()
          } catch (e: ProcessDeath) {
            assertThat(death).isTrue()
          }
          fs.disarmFault()
          if (death) s.restartPhone()

          // Il giro dopo (e un altro, a cinque minuti): tutti allo stesso stato.
          repeat(3) {
            s.world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
            s.phone.syncOnce()
            s.tablet.syncOnce()
          }
          val label = "guasto alla richiesta $at (dopo=$after, morte=$death)"
          val expected = s.keys + flagKey(mediaId(6), PersonalFields.FAVORITE)
          assertWithMessage(label).that(s.phone.store.entries.value.keys).containsExactlyElementsIn(expected)
          assertWithMessage(label).that(s.tablet.store.entries.value).isEqualTo(s.phone.store.entries.value)
          // Teste: al massimo una per cambiamento reale (due), mai una per tentativo.
          assertWithMessage(label).that(s.phone.headFiles().size).isAtMost(2)
          // Un dispositivo nuovo che legge SOLO dal NAS trova lo stesso stato e nessun segmento mancante o scartato.
          val reader = s.world.device()
          val read = reader.syncOnce()
          assertWithMessage(label).that(read.rejectedSegments).isEqualTo(0)
          assertWithMessage(label).that(read.rejectedHeads).isEqualTo(0)
          assertWithMessage(label).that(reader.store.entries.value).isEqualTo(s.phone.store.entries.value)
          runs++
        }
      }
    }
    println("P13 prova dei guasti: $runs giri su $calls richieste")
  }

  // --- Tetti di lettura ------------------------------------------------------------------------------------------------------------

  private fun bulk(owner: String, from: Int, count: Int, world: PersonalWorld) = (from until from + count).map {
    PersonalEntry(flagKey(mediaId(it), PersonalFields.FAVORITE), world.clock.now - 10_000, owner, PersonalValue.Flag(true))
  }

  @Test
  fun unaTestaOltreIlTettoDiByteDelDispositivoNonSiScaricaEMaiTroppo() = runTest {
    val world = PersonalWorld()
    val tablet = world.device()
    tablet.store.applyRemote(bulk(tablet.id, 0, 400, world))
    tablet.syncOnce()
    val phone = world.device(limits = SyncLimits(maxBytesPerDevice = 3_000))
    val reads = world.fs("test").reads
    val report = phone.syncOnce()
    assertThat(report.rejectedSegments).isEqualTo(1)
    assertThat(report.applied).isEqualTo(0)
    // Nessun segmento letto: si e' deciso dalla dimensione dichiarata dalla testa firmata.
    val readsOfSegments = world.fs("test").reads - reads
    assertThat(readsOfSegments).isAtMost(4)
    assertThat(phone.store.entries.value).isEmpty()
  }

  @Test
  fun unaTestaConTroppeVociSiLeggeFinoAlTettoEIlResto_siScarta() = runTest {
    val world = PersonalWorld()
    val tablet = world.device()
    tablet.store.applyRemote(bulk(tablet.id, 0, 3_000, world))
    tablet.syncOnce()
    val segmentCount = tablet.headFiles().size
    val phone = world.device(limits = SyncLimits(maxEntriesPerDevice = 100))
    val report = phone.syncOnce()
    assertThat(report.rejectedSegments).isGreaterThan(0)
    assertThat(phone.store.entries.value.size).isLessThan(3_000)
    assertThat(phone.store.entries.value.size).isAtLeast(1)
    assertThat(segmentCount).isEqualTo(1)
  }

  @Test
  fun ilTettoDiByteDelGiroSiRiprendeAlGiroDopo() = runTest {
    val world = PersonalWorld()
    val first = Forger(world, world.device().signer, "test")
    val second = Forger(world, world.device().signer, "test")
    val a = bulk(first.id, 0, 200, world)
    first.publishState("test", a)
    second.publishState("test", bulk(second.id, 1_000, 200, world))
    val one = PersonalSegments.plan(a).segments.sumOf { it.bytes.size.toLong() }

    // Il tetto del giro fa entrare una testa sola: l'altra si legge al giro dopo (e la prima non si riscarica).
    val phone = world.device(limits = SyncLimits(maxBytesPerRound = one + one / 2))
    val r1 = phone.syncOnce()
    assertThat(r1.applied).isEqualTo(200)
    assertThat(phone.store.entries.value).hasSize(200)
    val r2 = phone.syncOnce()
    assertThat(r2.applied).isEqualTo(200)
    assertThat(phone.store.entries.value).hasSize(400)
  }

  // --- Tempi -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun centomilaVociSiPubblicanoESiLeggonoSuUnAltroDispositivoInTempiRagionevoli() = runTest {
    val world = PersonalWorld()
    val phone = world.device()
    val tablet = world.device()
    phone.store.applyRemote(bulk(phone.id, 0, 100_000, world))

    val t0 = System.nanoTime()
    val published = phone.syncOnce(Duration.INFINITE)
    val publishMs = (System.nanoTime() - t0) / 1_000_000
    assertThat(published.published).isEqualTo(100_000)
    assertThat(published.stoppedBy).isEqualTo(PersonalStop.COMPLETE)

    val t1 = System.nanoTime()
    val read = tablet.syncOnce(Duration.INFINITE)
    val readMs = (System.nanoTime() - t1) / 1_000_000
    assertThat(read.applied).isEqualTo(100_000)
    assertThat(tablet.store.entries.value).hasSize(100_000)

    // Il registro su disco con tutto lo stato (scritto a ogni modifica o giro che cambia qualcosa).
    val file = File(folder.newFolder(), "personal.ledger")
    val ledger = FilePersonalLedger(file)
    val t2 = System.nanoTime()
    ledger.save(LedgerSnapshot(entries = tablet.store.entries.value))
    val saveMs = (System.nanoTime() - t2) / 1_000_000
    val t3 = System.nanoTime()
    val back = ledger.load()
    val loadMs = (System.nanoTime() - t3) / 1_000_000
    assertThat(back.entries).hasSize(100_000)

    // Un giro senza novita' con tanto stato non rilegge niente.
    val idle0 = System.nanoTime()
    val idle = tablet.syncOnce()
    val idleMs = (System.nanoTime() - idle0) / 1_000_000
    assertThat(idle.applied).isEqualTo(0)
    println(
      "P13 100.000 voci: pubblica $publishMs ms, legge $readMs ms, registro su disco ${file.length() / 1024} KiB " +
        "(scrive $saveMs ms, legge $loadMs ms), giro senza novita' $idleMs ms",
    )
    assertThat(publishMs).isLessThan(30_000)
    assertThat(readMs).isLessThan(30_000)
    assertThat(saveMs).isLessThan(10_000)
    assertThat(loadMs).isLessThan(10_000)
  }
}
