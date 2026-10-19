package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceWarning
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.noop.NoFreeSpace
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryFreeSpace
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.backup.testing.uuidOf
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 5B (P0-B, D21, §2.5, E-S11, L39): il contratto di `FreeSpace` sul fake di riferimento (`InMemoryFreeSpace`), che P12 usa per la UI e P9
 * rifara' con il NAS. Sono le regole che il "Fatto quando" di 5.5 chiede alla logica vera: un elemento con la copia rovinata o cambiata dopo
 * la verifica non finisce mai tra quelli da liberare, la lista non cresce mai, la conferma vale per questo piano, l'avviso forte c'e' sempre.
 */
class FiveBFreeSpaceTest {

  private val clock = FakeBackupClock()
  private val space = InMemoryFreeSpace(clock, todayLocalDay = 20_261_019)

  private fun item(n: Long, day: Int? = 20_240_101, video: Boolean = false, size: Long = 1_000_000) = FreeSpaceItem(
    mediaId = MediaId(uuidOf(n)),
    versionId = VersionId(uuidOf(n + 5_000)),
    phone = PhoneRef("external_primary", n, "v1"),
    mime = if (video) "video/mp4" else "image/jpeg",
    size = size,
    safeCopy = NasStamp(size, 7_000, n),
    isVideo = video,
    localDay = day,
  )

  private suspend fun confirmed(filter: FreeSpaceFilter = FreeSpaceFilter(), permanent: Boolean = false) =
    space.plan(filter, permanent).let { it to TestConfirmations.confirm(it, clock.monotonicMs()) }

  @Test
  fun ilPianoEscludeGliUltimiTrentaGiorniEFiltraPerTipoEPerEta() = runTest {
    space.candidates += listOf(
      item(1, day = 20_261_010), // 9 giorni fa: recente
      item(2, day = 20_240_101), // vecchia, foto
      item(3, day = 20_240_201, video = true), // vecchio, video
      item(4, day = 20_261_001, video = true), // 18 giorni fa: recente
      item(5, day = 20_260_601), // 4 mesi fa: sicura ma non "piu' vecchia di un anno"
      item(6, day = null), // senza data: mai recente per sbaglio, mai "vecchia di un anno"
    )
    val all = space.plan(FreeSpaceFilter())
    assertThat(all.items.map { it.phone.mediaStoreId }).containsExactly(2L, 3L, 5L, 6L).inOrder()
    assertThat(all.excluded).containsExactly(FreeSpaceExclusion.RECENT, 2)
    assertThat(space.plan(FreeSpaceFilter(FreeSpaceKind.VIDEOS_ONLY)).items.map { it.phone.mediaStoreId }).containsExactly(3L)
    assertThat(space.plan(FreeSpaceFilter(FreeSpaceKind.OLDER_THAN_A_YEAR)).items.map { it.phone.mediaStoreId }).containsExactly(2L, 3L)
    // Con 0 giorni esclusi tornano anche i recenti (la scelta esplicita dell'utente).
    assertThat(space.plan(FreeSpaceFilter(excludeRecentDays = 0)).itemCount).isEqualTo(6)
    // La selezione dalla Libreria restringe, non allarga.
    assertThat(space.plan(FreeSpaceFilter(selection = setOf(MediaId(uuidOf(2)), MediaId(uuidOf(1))))).items.map { it.phone.mediaStoreId }).containsExactly(2L)
  }

  @Test
  fun gliEsclusiDelCatalogoNonEntranoMaiEL_AvvisoForteEInOgniPiano() = runTest {
    space.candidates += listOf(item(1), item(2), item(3))
    space.excludedMedia[MediaId(uuidOf(2))] = FreeSpaceExclusion.NOT_VERIFIED
    space.excludedMedia[MediaId(uuidOf(3))] = FreeSpaceExclusion.VERSION_NOT_SAFE
    val plan = space.plan(FreeSpaceFilter())
    assertThat(plan.items.map { it.phone.mediaStoreId }).containsExactly(1L)
    assertThat(plan.excluded).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1, FreeSpaceExclusion.VERSION_NOT_SAFE, 1)
    // C13: l'avviso forte sull'assenza di backup esterno c'e' sempre, anche in un piano vuoto e nel null-object.
    assertThat(plan.warnings).contains(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    assertThat(InMemoryFreeSpace().plan(FreeSpaceFilter()).warnings).contains(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    assertThat(NoFreeSpace.plan(FreeSpaceFilter()).warnings).contains(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    assertThat(NoFreeSpace.plan(FreeSpaceFilter()).isEmpty).isTrue()
  }

  @Test
  fun unaCopiaCheNonTornaAllaVerificaOCambiaPrimaDelDialogoNonEMaiTraQuelleDaLiberare() = runTest {
    space.candidates += (1L..5L).map { item(it) }
    space.failVerification += MediaId(uuidOf(2)) // E-B8: la rilettura non torna
    space.changeAfterVerification += MediaId(uuidOf(4)) // L39: la copia cambia fra la verifica e il dialogo
    val (plan, confirmation) = confirmed()
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    assertThat(run.readyItems).isEqualTo(4)

    val batch = space.nextBatch(run)!!
    // Nel lotto solo cio' che era nel piano E ha superato la verifica E ha ancora il timbro verificato: una verifica toglie, mai aggiunge (E-S11).
    assertThat(batch.items.map { it.phone.mediaStoreId }).containsExactly(1L, 3L, 5L).inOrder()
    assertThat(batch.items.map { it.mediaId }).containsNoneIn(listOf(MediaId(uuidOf(2)), MediaId(uuidOf(4))))
    assertThat(batch.mode).isEqualTo(TrashMode.ANDROID_TRASH)
    assertThat(plan.items.map { it.phone.mediaStoreId }).containsAtLeastElementsIn(batch.items.map { it.phone.mediaStoreId })
  }

  @Test
  fun lConfermaVaConIlPianoDiAdessoEUnPianoCambiatoBloccaIlGiro() = runTest {
    space.candidates += listOf(item(1), item(2))
    val (plan, confirmation) = confirmed()
    // Fra il foglio e il tocco arriva un elemento in piu' (una verifica del backup, un altro dispositivo): la conferma non vale.
    space.candidates += item(3)
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(run.blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)
    assertThat(space.nextBatch(run)).isNull()
    // Una conferma scaduta (piu' di 10 minuti) non vale.
    val (plan2, old) = confirmed()
    clock.advance(11 * 60 * 1000L)
    assertThat(space.verify(plan2, old, 5.minutes).blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)
  }

  @Test
  fun laVerificaValeSeiOreMonotoneENonDiPiu() = runTest {
    space.candidates += listOf(item(1), item(2))
    val (plan, confirmation) = confirmed()
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.validUntilMonotonicMs).isEqualTo(clock.monotonicMs() + FreeSpaceRules.VERIFICATION_VALID_MS)
    clock.advance(FreeSpaceRules.VERIFICATION_VALID_MS - 1)
    assertThat(space.resume(run, 1.minutes).state).isEqualTo(FreeSpaceRunState.READY)
    clock.advance(2)
    assertThat(space.resume(run, 1.minutes).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    // Scaduta, non esce nessun lotto: si rifa' dal piano.
    assertThat(space.nextBatch(run)).isNull()
  }

  @Test
  fun iLottiSonoDaDuemilaEIlDialogoConfermaSoloUnaParte() = runTest {
    space.candidates += (1L..2_500L).map { item(it, size = 10) }
    val (plan, confirmation) = confirmed(FreeSpaceFilter())
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.batchesTotal).isEqualTo(2)

    val first = space.nextBatch(run)!!
    assertThat(first.items).hasSize(FreeSpaceRules.MAX_BATCH)
    assertThat(first.index).isEqualTo(1)
    // Android conferma solo una parte (500 righe): solo quelle contano come liberate.
    val confirmedRows = first.items.take(500).map { it.phone }.toSet()
    val afterFirst = space.recordOutcome(first, TrashOutcome.Confirmed(confirmedRows))
    assertThat(afterFirst.freedItems).isEqualTo(500)
    assertThat(afterFirst.freedBytes).isEqualTo(5_000)
    assertThat(afterFirst.state).isEqualTo(FreeSpaceRunState.READY)

    val second = space.nextBatch(afterFirst)!!
    assertThat(second.items).hasSize(500)
    assertThat(second.index).isEqualTo(2)
    val done = space.recordOutcome(second, TrashOutcome.Confirmed(second.items.map { it.phone }.toSet()))
    assertThat(done.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(done.freedItems).isEqualTo(1_000)
    assertThat(space.nextBatch(done)).isNull()
  }

  @Test
  fun seLUtenteRifiutaIlDialogoNienteAltroSiChiedeEUnaRispostaPersaNonContaComeLiberato() = runTest {
    space.candidates += (1L..3L).map { item(it) }
    val (plan, confirmation) = confirmed()
    val run = space.verify(plan, confirmation, 5.minutes)
    val batch = space.nextBatch(run)!!
    val unknown = space.recordOutcome(batch, TrashOutcome.Unknown)
    // L'app era morta durante il dialogo: nessun elemento si dichiara liberato senza la prova della lettura del telefono.
    assertThat(unknown.freedItems).isEqualTo(0)

    val (plan2, confirmation2) = confirmed()
    val run2 = space.verify(plan2, confirmation2, 5.minutes)
    val refused = space.recordOutcome(space.nextBatch(run2)!!, TrashOutcome.Refused)
    assertThat(refused.state).isEqualTo(FreeSpaceRunState.CANCELLED)
    assertThat(space.nextBatch(refused)).isNull()
  }

  @Test
  fun libereSubitoVuoleDueGestiEFaIlDialogoDefinitivo() = runTest {
    space.candidates += item(1)
    val (plan, confirmation) = confirmed(permanent = true)
    assertThat(confirmation.confirmedSteps).isEqualTo(2)
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.permanent).isTrue()
    assertThat(space.nextBatch(run)!!.mode).isEqualTo(TrashMode.DELETE_NOW)
  }

  @Test
  fun unaAreaCongelataOUnNasIrraggiungibileBloccanoIlGiro() = runTest {
    space.candidates += item(1)
    val (plan, confirmation) = confirmed()
    space.block = FreeSpaceBlock.AREA_FROZEN
    val run = space.verify(plan, confirmation, 5.minutes)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(run.blocked).isEqualTo(FreeSpaceBlock.AREA_FROZEN)
    assertThat(space.nextBatch(run)).isNull()
    // Il null-object non libera niente: "non disponibile", nessun lotto.
    val nothing = NoFreeSpace.verify(plan, confirmation, 1.minutes)
    assertThat(nothing.blocked).isEqualTo(FreeSpaceBlock.NOT_AVAILABLE)
    assertThat(NoFreeSpace.nextBatch(nothing)).isNull()
  }
}
