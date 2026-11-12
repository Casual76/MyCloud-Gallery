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

  /**
   * Fase I (BS-1, BF-3): la chiave comprende account, area privata e scansione finita o no. Un'altra area (l'admin ne assegna una nuova, mai scandita) a
   * parita' di sorgenti e conteggi non riusa il riepilogo (i numeri dell'area di prima, con i tasti accesi): si ricalcola, e il piano dice «non scandita».
   * La fine della scansione cambia la chiave: numeri e cancello si rifanno insieme al primo ricalcolo, non dopo un minuto.
   */
  @Test
  fun unAltraAreaOLaFineDellaScansioneRifannoIlRiepilogo() {
    val counts = dev.pampa.mycloudgallery.core.backup.contract.SafetyCounts.ZERO
    fun key(account: String?, share: String?, scanned: Boolean) = SummaryCache.keyOf(listOf("camera", "screenshots"), true, counts, account, share, scanned)
    val cache = SummaryCache(minute)
    cache.put(key("mario", "Mario", scanned = true), 0, settled)
    assertThat(cache.fresh(key("mario", "Mario", scanned = true), 10 * minute)).isSameInstanceAs(settled)
    // Stesse sorgenti, stessi conteggi, area nuova mai scandita: si ricalcola.
    assertThat(cache.fresh(key("mario", "Mario-nuova", scanned = false), 0)).isNull()
    // Anche a parita' di scansione finita: un'altra area e' un altro riepilogo (mai i numeri dell'area di prima).
    assertThat(cache.fresh(key("mario", "Mario-nuova", scanned = true), 0)).isNull()
    // Un altro account: si ricalcola.
    assertThat(cache.fresh(key("anna", "Mario", scanned = true), 0)).isNull()
    // La scansione dell'area finisce: il riepilogo provvisorio di prima non vale piu', anche entro il suo minuto.
    cache.put(key("mario", "Mario", scanned = false), 0, provisional)
    assertThat(cache.fresh(key("mario", "Mario", scanned = true), 1_000)).isNull()
    // L'ordine delle sorgenti non conta.
    assertThat(SummaryCache.keyOf(listOf("b", "a"), true, counts, "m", "s", true)).isEqualTo(SummaryCache.keyOf(listOf("a", "b"), true, counts, "m", "s", true))
  }

  /** Fase I (BS-1): le parti del backup cambiano (un'altra area, il NAS che va e viene): niente di prima vale, nemmeno come «ultimo». */
  @Test
  fun cambiateLePartiLaCacheSiSvuota() {
    val cache = SummaryCache(minute)
    cache.put("k", 0, settled)
    cache.clear()
    assertThat(cache.fresh("k", 0)).isNull()
    assertThat(cache.last()).isNull()
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
