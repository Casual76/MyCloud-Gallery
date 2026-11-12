package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

  /**
   * Fase I (BS2-3): il pianificatore delle parti di prima e' sospeso nel suo calcolo (secondi in un processo nuovo); intanto `start` ricostruisce le parti per
   * un'altra area e svuota la cache. Il vecchio pianificatore finisce **dopo** lo svuotamento: il suo riepilogo non entra in cache e non si restituisce (null:
   * chi chiama dice «sto contando» e ricalcola). Prima `put` lo rimetteva in cache dopo `clear`, sotto la chiave nuova, e i tasti si accendevano sui numeri
   * dell'area di prima. Cadrebbe senza la generazione (o con un `clear` che non la fa crescere).
   */
  @Test
  fun unCalcoloFinitoDopoLoSvuotamentoNonEntraInCacheENonSiRestituisce() = runBlocking<Unit> {
    val cache = SummaryCache(minute)
    val generation = cache.generation()
    val planner = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    val old = async(start = CoroutineStart.UNDISPATCHED) {
      cache.summaryOf("k", 0, generation, stillCurrent = { true }) {
        started.complete(Unit)
        planner.await()
        settled
      }
    }
    started.await()
    // Le parti cambiano mentre il vecchio pianificatore calcola: la cache si svuota (e la generazione cresce).
    cache.clear()
    planner.complete(Unit)
    assertThat(old.await()).isNull()
    assertThat(cache.fresh("k", 0)).isNull()
    assertThat(cache.last()).isNull()
  }

  /** BS2-3: le parti lette all'inizio non sono piu' quelle di adesso alla fine del calcolo (lo dice chi chiama): niente cache, niente risultato. */
  @Test
  fun lePartiCambiateDuranteIlCalcoloNonDannoUnRiepilogo() = runBlocking<Unit> {
    val cache = SummaryCache(minute)
    var same = true
    val result = cache.summaryOf("k", 0, cache.generation(), stillCurrent = { same }) {
      same = false
      settled
    }
    assertThat(result).isNull()
    assertThat(cache.last()).isNull()
  }

  /** BS2-3: con la stessa generazione il riepilogo si calcola una volta e poi viene dalla cache; con una generazione vecchia, nemmeno quello in cache. */
  @Test
  fun stessaGenerazioneUnCalcoloSolo_GenerazioneVecchiaNiente() = runBlocking<Unit> {
    val cache = SummaryCache(minute)
    val calls = AtomicInteger()
    val first = cache.generation()
    assertThat(cache.summaryOf("k", 0, first, { true }) { calls.incrementAndGet(); settled }).isSameInstanceAs(settled)
    assertThat(cache.summaryOf("k", 1_000, first, { true }) { calls.incrementAndGet(); settled }).isSameInstanceAs(settled)
    assertThat(calls.get()).isEqualTo(1)
    cache.clear()
    val second = cache.generation()
    assertThat(second).isGreaterThan(first)
    assertThat(cache.summaryOf("k", 0, second, { true }) { calls.incrementAndGet(); settled }).isSameInstanceAs(settled)
    // Chi aveva letto le parti con la generazione di prima non riceve nemmeno il riepilogo in cache della generazione nuova.
    assertThat(cache.summaryOf("k", 0, first, { true }) { calls.incrementAndGet(); settled }).isNull()
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
