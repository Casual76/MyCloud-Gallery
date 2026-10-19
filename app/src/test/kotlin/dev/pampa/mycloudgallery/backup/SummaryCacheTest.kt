package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import org.junit.Test

/**
 * **BK-8, BK-13** (verifica funzionale): la cache del riepilogo del primo backup. Un riepilogo "assestato" non resta quello di una settimana
 * fa (la chiave comprende cio' che il backup ha fatto), e uno "provvisorio" ("sto cercando cosa c'e' gia' sul NAS") scade e dice quando
 * ricontrollare: senza un altro evento lo stato non sarebbe mai diventato "assestato".
 */
class SummaryCacheTest {

  private val minute = 60_000L
  private val settled = BackupSummary.EMPTY
  private val provisional = BackupSummary.EMPTY.copy(existing = ExistingCopiesState(privateAreaScanned = false, similarPending = 0))

  @Test
  fun unRiepilogoDefinitivoValeFinoAlCambioDellaChiave() {
    val cache = SummaryCache(minute)
    cache.put("a|3 al sicuro", 0, settled)

    assertThat(cache.fresh("a|3 al sicuro", 10 * 60 * minute)).isSameInstanceAs(settled)
    // Il backup ha fatto strada (la chiave comprende i media al sicuro e in coda): si ricalcola, anche a ora invariata.
    assertThat(cache.fresh("a|4 al sicuro", 0)).isNull()
    assertThat(cache.recheckInMs(0)).isNull()
  }

  @Test
  fun unRiepilogoProvvisorioScadeDopoUnMinutoEDiceQuandoRicontrollare() {
    val cache = SummaryCache(minute)
    cache.put("k", 1_000, provisional)

    assertThat(cache.fresh("k", 1_000 + minute - 1)).isSameInstanceAs(provisional)
    assertThat(cache.recheckInMs(1_000 + 20_000)).isEqualTo(minute - 20_000)
    assertThat(cache.fresh("k", 1_000 + minute)).isNull()
    // Scaduto: ricontrollare subito (mai un'attesa negativa o nulla).
    assertThat(cache.recheckInMs(1_000 + 5 * minute)).isEqualTo(1L)
  }

  @Test
  fun senzaNienteInCacheNonCeNullaDaRicontrollare_EL_ultimoCalcolatoResta() {
    val cache = SummaryCache(minute)
    assertThat(cache.fresh("k", 0)).isNull()
    assertThat(cache.recheckInMs(0)).isNull()
    assertThat(cache.last()).isNull()

    cache.put("k", 0, settled)
    assertThat(cache.last()).isSameInstanceAs(settled)
    // Anche con la chiave cambiata l'ultimo e' disponibile: serve a una stima che non puo' aspettare.
    assertThat(cache.fresh("altra", 0)).isNull()
    assertThat(cache.last()).isSameInstanceAs(settled)
  }
}
