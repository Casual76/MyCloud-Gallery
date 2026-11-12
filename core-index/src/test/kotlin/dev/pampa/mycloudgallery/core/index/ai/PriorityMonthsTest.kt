package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.TrustWorkLeases
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * XS-2 (Pacchetto XF): **il lavoro ordinario non si ferma per delle urgenze che non si possono servire.** Con le unita' vere
 * (`LeasedWorkUnits`, prenotazioni firmate sul NAS finto, `AiWorkSource` e `AiPublishedClasses` come in produzione) 48 media chiesti
 * dall'utente in 48 mesi distinti riempivano da soli la finestra dei 48 mesi guardati a ogni chiamata: i mesi ordinari, anche con le
 * anteprime pronte, non venivano mai esaminati e una tappa nuova ricominciava dalla stessa situazione. Ora al piu' [PRIORITY] mesi con media
 * prioritari passano davanti e il resto della finestra e' sempre dei mesi ordinari.
 *
 * La mutazione che la prova ha ucciso: togliere il tetto dei mesi prioritari (`take(maxPriorityMonthsPerCall)`) fa cadere
 * `quarantottoMesiUrgentiNonEseguibiliNonFermanoIlMeseOrdinario`.
 */
class PriorityMonthsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  private class World(val ai: AiWorld, val urgent: List<TestFile>, val ordinary: List<TestFile>, val catalog: MapAiWorkCatalog)

  /** [urgentMonths] mesi vecchi con un media ciascuno (le richieste dell'utente) e un mese recente ordinario con [ordinaryFiles] media. */
  private fun world(urgentMonths: Int, ordinaryFiles: Int = 3): World {
    val ai = AiWorld(tmp.newFolder())
    val urgent = (1..urgentMonths).map { ai.file(share, "vecchi/IMG_u$it.jpg") }
    val ordinary = (1..ordinaryFiles).map { ai.file(share, "recenti/IMG_o$it.jpg") }
    val months = LinkedHashMap<String, List<TestFile>>()
    urgent.forEachIndexed { i, f -> months["%04d-%02d".format(2015 + i / 12, i % 12 + 1)] = listOf(f) }
    months["2026-09"] = ordinary
    return World(ai, urgent, ordinary, MapAiWorkCatalog(months))
  }

  private fun units(w: World, quota: Int?): LeasedWorkUnits {
    val device = w.ai.device("Telefono")
    val remote = device.remote()
    val published = AiPublishedClasses(device.shareSync(remote), device.aiStore) { 1 }
    val leases = TrustWorkLeases({ w.ai.world.nas.share(it) }, device.base.signer, w.ai.clock, { w.ai.world.trust.state }, Dispatchers.Unconfined)
    return if (quota == null) {
      LeasedWorkUnits(device.identity, AiWorkSource(w.catalog) { setOf(AiClass.VEC) }, published, leases, device.rules())
    } else {
      LeasedWorkUnits(device.identity, AiWorkSource(w.catalog) { setOf(AiClass.VEC) }, published, leases, device.rules(), maxPriorityMonthsPerCall = quota)
    }
  }

  /** Come fa una tappa: prende le unita' una dopo l'altra **senza finirle** (restano prese: i media chiesti non si possono lavorare) finche' non ce ne sono piu'. */
  private suspend fun claimAll(units: LeasedWorkUnits, priority: FakeUnitPriority): List<UnitClaim> {
    val out = ArrayList<UnitClaim>()
    while (true) {
      out += units.next(share, WorkPass.AI, priority) ?: break
      if (out.size > 200) error("troppe unita': la tappa non finisce")
    }
    return out
  }

  private fun priorityOf(files: List<TestFile>) = FakeUnitPriority().also { it.visible.value = files.map { f -> f.contentKey.short }.toSet() }

  @Test
  fun quarantottoMesiUrgentiNonEseguibiliNonFermanoIlMeseOrdinario() = runTest {
    val w = world(urgentMonths = LeasedWorkUnits_MONTHS)
    val claims = claimAll(units(w, quota = PRIORITY), priorityOf(w.urgent))

    // Le richieste vengono prima (al massimo PRIORITY mesi), ma il mese ordinario eseguibile avanza comunque.
    val ordinaryAt = claims.indexOfFirst { it.key.month == "2026-09" }
    assertThat(ordinaryAt).isAtLeast(0)
    assertThat(ordinaryAt).isAtMost(PRIORITY)
    assertThat(claims[ordinaryAt].items.map { it.contentKey }).containsExactlyElementsIn(w.ordinary.map { it.contentKey })
    // E le prime unita' sono davvero quelle delle richieste (la precedenza resta).
    assertThat(claims.take(PRIORITY).all { claim -> claim.items.any { it.contentKey in w.urgent.map { f -> f.contentKey } } }).isTrue()
  }

  @Test
  fun senzaIlTettoLeUrgenzeRiempionoLaFinestraEIlMeseOrdinarioNonArrivaMai() = runTest {
    // Il difetto di prima, riprodotto con il valore "nessun tetto" (come le unita' della preparazione): serve a mostrare che la prova di sopra misura qualcosa.
    val w = world(urgentMonths = LeasedWorkUnits_MONTHS)
    val claims = claimAll(units(w, quota = null), priorityOf(w.urgent))
    assertThat(claims.none { it.key.month == "2026-09" }).isTrue()
    assertThat(claims).hasSize(LeasedWorkUnits_MONTHS)
  }

  @Test
  fun conPiuDiQuarantottoRichiesteIlMeseOrdinarioAvanzaALoStesso() = runTest {
    // Il registro tiene 64 richieste: anche con 64 mesi urgenti il mese ordinario recente entra in ogni tappa.
    val w = world(urgentMonths = 64)
    val claims = claimAll(units(w, quota = PRIORITY), priorityOf(w.urgent))
    assertThat(claims.any { it.key.month == "2026-09" }).isTrue()
  }

  @Test
  fun senzaRichiesteIlTettoNonCambiaNiente() = runTest {
    // Il comportamento senza richieste dell'utente e' identico a prima: stessa sequenza di unita' con o senza il tetto.
    val w = world(urgentMonths = 20)
    val none = FakeUnitPriority()
    val withQuota = claimAll(units(w, quota = PRIORITY), none).map { it.key }
    val w2 = world(urgentMonths = 20)
    val without = claimAll(units(w2, quota = null), FakeUnitPriority()).map { it.key }
    assertThat(withQuota).isNotEmpty()
    assertThat(withQuota).isEqualTo(without)
    // Le unita' si prendono dal mese piu' recente, come sempre.
    assertThat(withQuota.first().month).isEqualTo("2026-09")
  }

  private companion object {
    const val PRIORITY = 16

    /** La finestra dei mesi guardati a ogni chiamata di `LeasedWorkUnits` (48). */
    const val LeasedWorkUnits_MONTHS = 48
  }
}
