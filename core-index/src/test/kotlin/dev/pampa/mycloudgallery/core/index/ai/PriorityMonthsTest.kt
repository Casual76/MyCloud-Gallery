package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
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
 * XS-2 e XS2-2 (Pacchetto XF): **il lavoro ordinario non si ferma per delle urgenze che non si possono servire.** Con le unita' vere
 * (`LeasedWorkUnits`, prenotazioni firmate sul NAS finto, `AiWorkSource` e `AiPublishedClasses` come in produzione):
 * - primo giro: 48 media chiesti in 48 mesi distinti riempivano da soli la finestra dei 48 mesi guardati a ogni chiamata (quota sul prefisso, `quota`);
 * - **secondo giro** (XS2-2): la quota sul prefisso non basta, perche' gli altri mesi di richieste rientravano nella lista ordinaria. Ora i mesi delle richieste e
 *   i mesi ordinari sono **due classi** servite **a turno** (`alternate`): l'ordinario ha servizio vero a ogni tappa, anche se e' il mese piu' **vecchio** e anche con 64
 *   richieste in 64 mesi recenti non servibili; i mesi di richieste che hanno finito senza dare lavoro (`STALLED`) passano in fondo e si avanza oltre di loro fra le tappe.
 *
 * Mutazioni (nel resoconto): togliere il tetto del prefisso (`quarantottoMesi...`), togliere dall'ordinario i mesi delle richieste (`conSessantaquattro...`),
 * togliere l'alternanza (`conSessantaquattro...`, `urgenzeRinnovate...`), togliere il rinvio dei mesi senza lavoro (`...`rotazione`).
 */
class PriorityMonthsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  private class World(
    val ai: AiWorld,
    val urgent: List<TestFile>,
    val ordinary: List<TestFile>,
    val urgentMonths: List<String>,
    val ordinaryMonths: List<String>,
    val catalog: MapAiWorkCatalog,
  )

  /**
   * [urgentMonths] mesi con un media chiesto ciascuno (le richieste dell'utente), vecchi o [recentRequests] recenti, e [ordinaryMonths] mesi ordinari con
   * [ordinaryFiles] media: di serie un solo mese recente, o (`oldestOrdinary`) mesi molto **vecchi**, piu' vecchi di tutte le richieste.
   */
  private fun world(
    urgentMonths: Int,
    ordinaryFiles: Int = 3,
    recentRequests: Boolean = false,
    ordinaryMonths: List<String> = listOf("2026-09"),
  ): World {
    val ai = AiWorld(tmp.newFolder())
    val urgent = (1..urgentMonths).map { ai.file(share, "richieste/IMG_u$it.jpg") }
    val ordinary = ordinaryMonths.flatMapIndexed { m, month -> (1..ordinaryFiles).map { ai.file(share, "ordinari/$month/IMG_o${m}_$it.jpg") } }
    val months = LinkedHashMap<String, List<TestFile>>()
    val requestMonths = urgent.indices.map { i ->
      val year = (if (recentRequests) 2031 else 2015) + i / 12
      "%04d-%02d".format(year, i % 12 + 1)
    }
    urgent.forEachIndexed { i, f -> months[requestMonths[i]] = listOf(f) }
    ordinaryMonths.forEachIndexed { m, month -> months[month] = ordinary.subList(m * ordinaryFiles, (m + 1) * ordinaryFiles) }
    return World(ai, urgent, ordinary, requestMonths, ordinaryMonths, MapAiWorkCatalog(months))
  }

  /** `alternate`: le due classi a turno (il calcolo AI di produzione); [quota] tetto dei mesi di richieste davanti; senza, il comportamento di sempre. */
  private fun units(w: World, alternate: Boolean, quota: Int? = null, clock: () -> Long = System::nanoTime): LeasedWorkUnits {
    val device = w.ai.device("Telefono")
    val remote = device.remote()
    val published = AiPublishedClasses(device.shareSync(remote), device.aiStore) { 1 }
    val leases = TrustWorkLeases({ w.ai.world.nas.share(it) }, device.base.signer, w.ai.clock, { w.ai.world.trust.state }, Dispatchers.Unconfined)
    val source = AiWorkSource(w.catalog) { setOf(AiClass.VEC) }
    return when {
      !alternate && quota == null -> LeasedWorkUnits(device.identity, source, published, leases, device.rules())
      else -> LeasedWorkUnits(
        device.identity, source, published, leases, device.rules(),
        maxPriorityMonthsPerCall = quota ?: 48, alternatePriority = alternate, clock = clock,
      )
    }
  }

  /** Come fa una tappa: prende le unita' una dopo l'altra **senza finirle** (restano prese: i media chiesti non si possono lavorare) finche' non ce ne sono piu'. */
  private suspend fun claimAll(units: LeasedWorkUnits, priority: FakeUnitPriority): List<UnitClaim> {
    val out = ArrayList<UnitClaim>()
    while (true) {
      out += units.next(share, WorkPass.AI, priority) ?: break
      if (out.size > 300) error("troppe unita': la tappa non finisce")
    }
    return out
  }

  private fun priorityOf(files: List<TestFile>) = FakeUnitPriority().also { it.visible.value = files.map { f -> f.contentKey.short }.toSet() }

  /**
   * XS3-1: **come il registro vero**: al piu' 16 chiavi passano davanti (`visible`, `AiUrgentFile.priority`), ma le richieste valide sono tutte (`requested`, fino a 64).
   * Il selettore riceve le due cose; una prova che gli passa tutte le richieste come `visible` non vede il difetto.
   */
  private fun priorityLikeRegistry(files: List<TestFile>, turn: Int = 16) = FakeUnitPriority().also {
    it.visible.value = files.take(turn).map { f -> f.contentKey.short }.toSet()
    it.requested.value = files.map { f -> f.contentKey.short }.toSet()
  }

  // ---- Primo giro (XS-2): la quota sul prefisso --------------------------------------------------------------------------------

  @Test
  fun quarantottoMesiUrgentiNonEseguibiliNonFermanoIlMeseOrdinario() = runTest {
    val w = world(urgentMonths = MONTHS)
    val claims = claimAll(units(w, alternate = true, quota = PRIORITY), priorityOf(w.urgent))

    // Le richieste vengono prima, ma il mese ordinario eseguibile avanza comunque: a turno, subito dopo la prima richiesta.
    val ordinaryAt = claims.indexOfFirst { it.key.month == "2026-09" }
    assertThat(ordinaryAt).isAtLeast(0)
    assertThat(ordinaryAt).isAtMost(1)
    assertThat(claims[ordinaryAt].items.map { it.contentKey }).containsExactlyElementsIn(w.ordinary.map { it.contentKey })
    // E la prima unita' e' quella di una richiesta (la precedenza resta).
    assertThat(claims.first().items.any { it.contentKey in w.urgent.map { f -> f.contentKey } }).isTrue()
  }

  @Test
  fun senzaIlTettoLeUrgenzeRiempionoLaFinestraEIlMeseOrdinarioNonArrivaMai() = runTest {
    // Il difetto di prima, riprodotto con il comportamento di sempre (la preparazione): serve a mostrare che le prove di sopra misurano qualcosa.
    val w = world(urgentMonths = MONTHS)
    val claims = claimAll(units(w, alternate = false), priorityOf(w.urgent))
    assertThat(claims.none { it.key.month == "2026-09" }).isTrue()
    assertThat(claims).hasSize(MONTHS)
  }

  // ---- Secondo giro (XS2-2) ------------------------------------------------------------------------------------------------------

  /** Il difetto del critico: 64 richieste in 64 mesi **recenti**, non servibili, e un mese ordinario **piu' vecchio** con le anteprime pronte. */
  @Test
  fun conSessantaquattroRichiesteRecentiIlMeseOrdinarioPiuVecchioAvanza() = runTest {
    val w = world(urgentMonths = 64, recentRequests = true, ordinaryMonths = listOf("2010-05"))
    val claims = claimAll(units(w, alternate = true, quota = PRIORITY), priorityLikeRegistry(w.urgent))
    val ordinaryAt = claims.indexOfFirst { it.key.month == "2010-05" }
    assertThat(ordinaryAt).isAtLeast(0)
    assertThat(ordinaryAt).isAtMost(1)
    assertThat(claims[ordinaryAt].items.map { it.contentKey }).containsExactlyElementsIn(w.ordinary.map { it.contentKey })
  }

  /**
   * Urgenze **rinnovate** a ogni tappa (le richieste non scadono) e **budget finito** (due unita' a tappa): a ogni tappa un mese ordinario avanza, anche il piu'
   * vecchio, e i mesi di richieste visitati sono sempre diversi (si avanza oltre quelli gia' tentati senza lavoro, fra le tappe).
   */
  @Test
  fun urgenzeRinnovateEBudgetFinitoOgniTappaServeUnMeseOrdinarioEFaAvanzareLeRichieste() = runTest {
    val ordinaryMonths = (1..6).map { "2010-%02d".format(it) }
    val w = world(urgentMonths = 64, recentRequests = true, ordinaryFiles = 1, ordinaryMonths = ordinaryMonths)
    val units = units(w, alternate = true, quota = PRIORITY)
    val servedOrdinary = ArrayList<String>()
    val visitedRequests = ArrayList<String>()
    repeat(ordinaryMonths.size) {
      // La tappa: le richieste sono le stesse (rinnovate) e il budget basta per due unita'.
      val priority = priorityLikeRegistry(w.urgent)
      repeat(2) {
        val claim = units.next(share, WorkPass.AI, priority) ?: return@repeat
        if (claim.key.month in ordinaryMonths) {
          servedOrdinary += claim.key.month
          claim.items.forEach { w.catalog.settled.getOrPut(it.contentKey) { HashSet() } += AiClass.VEC }
          units.finish(claim, UnitOutcome.Completed(claim.items.size, 0))
        } else {
          visitedRequests += claim.key.month
          units.finish(claim, UnitOutcome.Interrupted(UnitOutcome.Interrupted.STALLED))
        }
      }
    }
    // Ogni tappa ha servito un mese ordinario (tutti e sei, compreso il piu' vecchio) e ha provato una richiesta nuova.
    assertThat(servedOrdinary).containsExactlyElementsIn(ordinaryMonths)
    assertThat(visitedRequests).hasSize(ordinaryMonths.size)
    assertThat(visitedRequests.toSet()).hasSize(ordinaryMonths.size)
  }

  /**
   * XS3-1 (il bloccante del giro 3): con **64 richieste** e solo **16** che hanno il turno (come l'uscita del registro vero), gli altri 48 mesi di richieste **non** sono
   * mesi ordinari: non riempiono la finestra ordinaria e il mese vecchio con il lavoro pronto avanza subito (alla prima o alla seconda chiamata). Mutazione: il selettore che
   * esclude dalla classe ordinaria solo i mesi di `visible` (cioe' i 16).
   */
  @Test
  fun leRichiesteCheAspettanoIlTurnoNonRiempionoLaFinestraOrdinaria() = runTest {
    val w = world(urgentMonths = 64, recentRequests = true, ordinaryMonths = listOf("2010-05"))
    val priority = priorityLikeRegistry(w.urgent)
    assertThat(priority.visible.value).hasSize(16)
    assertThat(priority.requested.value).hasSize(64)
    val units = units(w, alternate = true, quota = PRIORITY)
    val first = units.next(share, WorkPass.AI, priority)!!
    val second = units.next(share, WorkPass.AI, priority)!!
    assertThat(listOf(first, second).map { it.key.month }).contains("2010-05")
  }

  /** I mesi di richieste senza lavoro passano in fondo e tornano alla fine del rinvio (30 minuti); senza richieste l'ordine e' quello di sempre. */
  @Test
  fun iMesiDiRichiesteSenzaLavoroPassanoInFondoETornanoDopoIlRinvio() = runTest {
    val w = world(urgentMonths = 3, recentRequests = true, ordinaryMonths = emptyList())
    var now = 0L
    val units = units(w, alternate = true, quota = PRIORITY, clock = { now })
    val priority = priorityOf(w.urgent)
    val order = ArrayList<String>()
    repeat(3) {
      val claim = units.next(share, WorkPass.AI, priority)!!
      order += claim.key.month
      units.finish(claim, UnitOutcome.Interrupted(UnitOutcome.Interrupted.STALLED))
    }
    // I tre mesi (dal piu' recente) a turno: nessuno si ripete finche' ci sono gli altri.
    assertThat(order.toSet()).hasSize(3)
    assertThat(order).isEqualTo(w.urgentMonths.sortedDescending())
    // Finito il rinvio tornano nell'ordine di sempre.
    now += 31L * 60 * 1_000_000_000
    assertThat(units.next(share, WorkPass.AI, priority)!!.key.month).isEqualTo(w.urgentMonths.max())
  }

  @Test
  fun senzaRichiesteIlTettoELAlternanzaNonCambianoNiente() = runTest {
    // Il comportamento senza richieste dell'utente e' identico a prima: stessa sequenza di unita' con o senza alternanza e tetto.
    val w = world(urgentMonths = 20)
    val none = FakeUnitPriority()
    val withClasses = claimAll(units(w, alternate = true, quota = PRIORITY), none).map { it.key }
    val w2 = world(urgentMonths = 20)
    val without = claimAll(units(w2, alternate = false), FakeUnitPriority()).map { it.key }
    assertThat(withClasses).isNotEmpty()
    assertThat(withClasses).isEqualTo(without)
    // Le unita' si prendono dal mese piu' recente, come sempre.
    assertThat(withClasses.first().month).isEqualTo("2026-09")
  }

  private companion object {
    const val PRIORITY = 16

    /** La finestra dei mesi guardati a ogni chiamata di `LeasedWorkUnits` (48). */
    const val MONTHS = 48
  }
}
