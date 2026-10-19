package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.backup.versions.VersionHold
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IL CATALOGO CHE RITARDA (giro 122 del banco JVM, seme di base 20261016, stack di riferimento), `docs/prova-500.md` Parte C §C6, O1.
 *
 * Lo scenario (`ChaosStaleScene.kt`): "Ripristina originale" (uno scambio) ha riportato l'originale da `Versioni/` al solito percorso e il processo
 * muore **dopo** la rinomina e prima di `RESTORED`; il **PC modifica sul posto** il file appena riportato (stesso `fileId`, **un'altra dimensione**);
 * il lavoro riprende, non riconosce il suo file (`sameIdentity`: fileId e dimensione) e finisce `FAILED`/`USUAL_PATH_TAKEN`. Il catalogo dice ancora
 * l'originale in `Versioni/`, dove non c'e' piu', finche' la scansione del NAS non lo dichiara assente (D10).
 *
 * Il coordinatore ha chiesto di NON far evitare lo scenario al banco e di rispondere, con lo scenario in mano, a quattro domande. Con la modifica di
 * un'altra dimensione la risposta e' **no** a tutte e quattro (questi test); con la modifica della **stessa** dimensione e' un'altra storia: F3,
 * in `ChaosKnownDefectsTest`.
 */
class StaleCatalogAfterPcEditTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun loScenario_loScambioFallisceIlCatalogoRitardaFinoAllaScansione() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = false)
    val job = s.fb.journal.all().single()
    assertThat(job.state).isEqualTo(UploadState.FAILED)
    assertThat(job.hold).isEqualTo(VersionHold.USUAL_PATH_TAKEN)
    // Il catalogo dice presente e verificata una copia dell'originale in Versioni/ dove sul NAS non c'e' piu' niente.
    val stale = s.copies().filter { (_, c) -> c.present && c.verifiedAtMillis != null && s.fb.stampOf(c.share, c.path) == null }
    assertWithMessage("copie presenti per il catalogo e assenti sul NAS: $stale").that(stale).hasSize(1)
    assertThat(stale.single().first.versionId).isEqualTo(s.fb.catalog.media(s.mediaId)!!.original!!.versionId)
    // Quel ritardo e' l'unica cosa che il verificatore trova: nessun altro invariante e' toccato.
    assertThat(s.verdict(Phase.FINAL).map { it.invariant }.toSet()).containsExactly(Invariant.CATALOG)
  }

  @Test
  fun domande1e2_nessunMediaAlSicuroConByteDiversiNessunaVersionePersaOSovrascrittaDallApp() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = false)
    // 1. Nessuna copia che il catalogo dice presente ha sul NAS byte diversi da quelli della sua versione: o il file c'e' ed e' giusto, o non c'e'.
    for ((version, copy) in s.copies().filter { it.second.present }) {
      val file = s.fb.nasBytes(copy.share, copy.path) ?: continue
      val truth = s.fb.shaOf(s.mediaId, version.versionId)
      assertWithMessage("la copia ${copy.path.value} ha altri byte").that(Sha256.of(file)).isEqualTo(truth)
    }
    // 2. Niente e' stato perso o sostituito dall'app: i soli byte che mancano sono quelli che il PC ha sovrascritto (nel file che ha modificato).
    //    Il verificatore a meta' (1, 2, 3, 12, 13...) non trova altro che il ritardo del catalogo (7).
    assertThat(s.verdict(Phase.MID).map { it.invariant }.toSet()).containsExactly(Invariant.CATALOG)
    val original = s.fb.catalog.media(s.mediaId)!!.original!!
    val shaOfOriginal = s.fb.shaOf(s.mediaId, original.versionId)
    assertThat(s.fb.lostShas).contains(shaOfOriginal)
    assertThat(s.bytes()).doesNotContainKey(shaOfOriginal)
    // Il ritorno all'originale (Samsung) ha reso attuale l'originale, la cui copia "verificata" non c'e' piu': il segno "al sicuro" e' in ritardo
    // (come per ogni cambiamento del PC fra due scansioni, D10) e la prima scansione lo toglie. Non e' un'altra cosa: nessuna copia ha byte diversi.
    assertThat(s.fb.catalog.media(s.mediaId)!!.currentVersionId).isEqualTo(original.versionId)
    assertThat(s.fb.catalog.mediaSafety(s.mediaId)!!.status.code).isEqualTo("safe")
    s.fb.rescan()
    assertThat(s.fb.catalog.mediaSafety(s.mediaId)!!.status.code).isNotEqualTo("safe")
  }

  @Test
  fun domanda3_liberaSpazioNonTogliePerNienteIlMediaConLaCopiaVecchia() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = false)
    assertThat(s.freedMedia()).doesNotContain(s.mediaId)
    // La riga del telefono e' ancora li'.
    assertThat(s.fb.currentRow(s.rec)).isNotNull()
  }

  @Test
  fun domanda4_unAltroScambioOUnaVersioneNuovaNonAgisconoSuDatiVecchiSenzaAccorgersene() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = false)
    val before = s.bytes()
    // Un altro giro di backup (che pianifica anche gli scambi dei ritorni) e poi una modifica nuova sul telefono, sempre nella finestra (senza
    // scansione del NAS in mezzo).
    val services = s.services()
    s.runVersions(services)
    s.fb.samsungSave(s.rec)
    s.fb.rescan()
    s.runVersions(services)
    // Nessun lavoro nuovo: senza una copia al solito percorso nel catalogo `VersionPlanning.head` non ha da dove cominciare (il media non ha piu' un
    // «solito percorso» finche' la scansione non lo ritrova), quindi niente parte su dati vecchi. Il lavoro di prima resta com'era: `FAILED` con il suo motivo.
    val jobs = s.fb.journal.all()
    assertWithMessage("lavori: ${jobs.joinToString { "${it.kind.code}:${it.state.code}:${it.hold?.code}" }}").that(jobs).hasSize(1)
    assertThat(jobs.single().state).isEqualTo(UploadState.FAILED)
    // Nessun byte di prima e' sparito e il file del PC e' ancora dov'era.
    val after = s.bytes()
    for ((sha, count) in before) assertWithMessage("byte spariti dal NAS").that(after[sha] ?: 0).isAtLeast(count)
    assertThat(s.fb.stampOf(s.world.share, s.usual)).isNotNull()
    // E il verificatore a meta' non trova altro che il ritardo del catalogo.
    assertThat(s.verdict(Phase.MID).map { it.invariant }.toSet() - Invariant.CATALOG).isEmpty()
  }

  @Test
  fun dopoLaScansioneIlCatalogoEInPassoETuttiGliInvariantiValgono() = runTest {
    val s = staleWindow(folder.newFolder(), keepSize = false)
    s.fb.rescan()
    assertThat(s.copies().filter { (_, c) -> c.present && s.fb.stampOf(c.share, c.path) == null }).isEmpty()
    assertThat(s.verdict(Phase.FINAL)).isEmpty()
  }
}

/**
 * I media che un giro di "Libera spazio" darebbe al dialogo di sistema adesso: il piano, la verifica sul momento, i lotti (il dialogo rifiuta, quindi
 * non si toglie niente). Il piano usa solo il catalogo locale (E-S11): vedere un media nel piano non vuol dire liberarlo.
 */
internal suspend fun StaleScene.freedMedia(): List<dev.pampa.mycloudgallery.core.model.MediaId> {
  val services = services()
  val plan = services.free.plan(FreeSpaceFilter(FreeSpaceKind.ALL, excludeRecentDays = 0), permanent = false)
  val confirmation = TestConfirmations.confirm(plan, world.clock.monotonicMs())
  var run = services.free.verify(plan, confirmation, Duration.ZERO)
  var stages = 0
  while (run.isActive && run.state != FreeSpaceRunState.READY && stages++ < 8) run = services.free.resume(run, 1.hours)
  val batches = ArrayList<TrashBatch>()
  if (run.state == FreeSpaceRunState.READY) {
    while (batches.size < 10) {
      val batch = services.free.nextBatch(run) ?: break
      batches += batch
      run = services.free.recordOutcome(batch, TrashOutcome.Refused)
    }
  }
  return batches.flatMap { b -> b.items.map { it.mediaId } }
}
