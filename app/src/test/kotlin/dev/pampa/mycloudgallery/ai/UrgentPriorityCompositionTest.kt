package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.index.work.PublishedClasses
import dev.pampa.mycloudgallery.core.index.work.PublishedProgress
import dev.pampa.mycloudgallery.core.index.work.WorkLeases
import dev.pampa.mycloudgallery.core.index.work.WorkSource
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.data.AcquireResult
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.nas.NasAiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * XS3-1 (Pacchetto XF, giro 3): **la composizione vera**: il registro delle richieste ([AiUrgentFile]) con 64 richieste -> la sua uscita limitata (al piu' 16 che passano
 * davanti) -> l'adattatore di produzione ([UrgentUnitPriority], quello che `AiGraph` consegna al selettore) -> il selettore vero ([LeasedWorkUnits], con la quota e
 * l'alternanza che mette `NasAi`). Prima la prova del selettore gli passava tutte e 64 le richieste come `visible`, mentre in produzione ne arrivavano 16: la classe
 * ordinaria escludeva solo 16 mesi su 64 e il mese ordinario vecchio restava fuori dalla finestra. Qui non c'e' nessuna porta finta fra il registro e il selettore: solo
 * le porte **di fuori** (l'elenco dei mesi del NAS, le teste pubblicate, le prenotazioni) sono finte.
 *
 * Mutazione: l'adattatore che non espone `requested` (o `requested = visible`), cioe' il collegamento a 16.
 */
class UrgentPriorityCompositionTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val share = "test-comune"

  /** Le prime 8 cifre esadecimali (la chiave ridotta, `ContentKey.short`) devono essere diverse da media a media. */
  private fun key(n: Int) = ContentKey(n.toString(16).padStart(16, '0') + "0".repeat(48))

  private class Source(val months: Map<String, List<ContentKey>>) : WorkSource {
    override suspend fun months(share: String, pass: WorkPass): List<String> = months.keys.sortedDescending()

    override suspend fun monthsOf(share: String, pass: WorkPass, visible: Set<Long>): List<String> =
      months.filterValues { keys -> keys.any { it.short in visible } }.keys.sortedDescending()

    override suspend fun monthItems(share: String, pass: WorkPass, month: String): List<WorkItem> = months[month].orEmpty().map { key ->
      WorkItem(
        key, SharePath.parseMediaPath("foto/${key.hex.take(16)}.jpg"), NasStamp(10L, 1_000L, null), MediaKind.PHOTO, "image/jpeg", null, null,
        missing = setOf(SegmentClass.ANALYSIS),
      )
    }
  }

  private object NothingPublished : PublishedClasses {
    override suspend fun refresh(share: String): RemoteResult<Unit> = RemoteResult.Ok(Unit)

    override fun progress(share: String, keys: Collection<ContentKey>): Map<ContentKey, PublishedProgress> = emptyMap()
  }

  private object NoLeases : WorkLeases {
    override suspend fun acquire(share: String, resource: String): AcquireResult = AcquireResult.Contended

    override suspend fun release(share: String, resource: String) = Unit
  }

  /** Un selettore vero come lo monta `NasAi.build`: stessa quota, stessa alternanza; senza prenotazioni (nessun registro che ci fa scrivere). */
  private fun selector(source: WorkSource): LeasedWorkUnits = LeasedWorkUnits(
    identity = IndexIdentity(FamilyKey.generate(), "prova") { null },
    source = source,
    published = NothingPublished,
    leases = NoLeases,
    rules = IndexWriteRules(FeatureFlags.RELEASE, MapWriteGate()),
    maxPriorityMonthsPerCall = NasAiRuntime.PRIORITY_MONTHS,
    alternatePriority = NasAiRuntime.ALTERNATE_PRIORITY,
  )

  @Test
  fun conSessantaquattroRichiesteNelRegistroVeroIlMeseOrdinarioVecchioAvanza() = runBlocking<Unit> {
    // 64 mesi recenti con un media chiesto ciascuno e un mese ordinario del 2010 con lavoro pronto.
    val requestKeys = (1..64).map { key(it) }
    val months = LinkedHashMap<String, List<ContentKey>>()
    requestKeys.forEachIndexed { i, k -> months["%04d-%02d".format(2031 + i / 12, i % 12 + 1)] = listOf(k) }
    val old = key(1000)
    months["2010-05"] = listOf(old)

    val urgent = AiUrgentFile(java.io.File(temp.root, AiUrgentFile.FILE))
    requestKeys.forEach { urgent.add(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val priority = UrgentUnitPriority(urgent, scope)
      // L'uscita del registro e' limitata: 16 passano davanti, ma le richieste sono tutte e 64.
      assertThat(priority.visible.value).hasSize(AiUrgentFile.PRIORITY_KEYS)
      assertThat(priority.requested.value).hasSize(64)
      assertThat(priority.requested.value).containsAtLeastElementsIn(priority.visible.value)

      val units = selector(Source(months))
      val claims = ArrayList<UnitClaim>()
      repeat(2) { claims += units.next(share, WorkPass.AI, priority)!! }
      // Il mese ordinario vecchio avanza subito: alla prima o alla seconda chiamata (l'alternanza).
      assertThat(claims.map { it.key.month }).contains("2010-05")
      assertThat(claims.first { it.key.month == "2010-05" }.items.map { it.contentKey }).containsExactly(old)
      claims.forEach { units.finish(it, UnitOutcome.Interrupted(UnitOutcome.Interrupted.STALLED)) }
    } finally {
      scope.cancel()
    }
  }

  /** Le richieste rinnovate a ogni tappa (il registro le rimette in testa) non fermano l'ordinario: a ogni tappa, a ogni chiamata, ne resta uno. */
  @Test
  fun conLeRichiesteRinnovateOgniDueChiamateUnaEOrdinaria() = runBlocking<Unit> {
    val requestKeys = (1..64).map { key(it) }
    val months = LinkedHashMap<String, List<ContentKey>>()
    requestKeys.forEachIndexed { i, k -> months["%04d-%02d".format(2031 + i / 12, i % 12 + 1)] = listOf(k) }
    val ordinary = (1..4).map { key(2000 + it) }
    ordinary.forEachIndexed { i, k -> months["2010-%02d".format(i + 1)] = listOf(k) }

    val urgent = AiUrgentFile(java.io.File(temp.root, AiUrgentFile.FILE))
    requestKeys.forEach { urgent.add(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val priority = UrgentUnitPriority(urgent, scope)
      val units = selector(Source(months))
      val served = ArrayList<String>()
      repeat(8) {
        val claim = units.next(share, WorkPass.AI, priority) ?: return@repeat
        served += claim.key.month
        units.finish(claim, UnitOutcome.Interrupted(UnitOutcome.Interrupted.STALLED))
      }
      // Fra le otto unita' prese, quattro sono dei mesi ordinari (un'unita' ogni due), anche se sono i piu' vecchi.
      assertThat(served.count { it.startsWith("2010-") }).isAtLeast(3)
    } finally {
      scope.cancel()
    }
  }
}
