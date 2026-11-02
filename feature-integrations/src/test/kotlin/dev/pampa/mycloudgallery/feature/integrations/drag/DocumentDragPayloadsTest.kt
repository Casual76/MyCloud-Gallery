package dev.pampa.mycloudgallery.feature.integrations.drag

import android.content.ClipData
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.DragFlags
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentClock
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentResolution
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentTarget
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.util.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 10 (P6, D20, C436; E-T11, E-T12, E-T17, E-T18, E-H11, E-X6, E-X7): `DocumentDragPayloads`, la porta `MediaDragHandler` sopra il provider dei documenti. URI solo del provider con id
 * opachi consegnati, solo lettura sul singolo URI, tetti detti prima di cominciare, mai un nascosto (con una porta che "sbaglia" e con un media nascosto a meta' preparazione), il blocco
 * dell'app, il NAS che non risponde senza attese infinite, l'annullamento che non lascia id, e il prestito che toglie tutto.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentDragPayloadsTest {

  private val library = FakeIntegrationLibrary()
  private val authority = "test.pkg.integrations.documents"
  private val clock = object : DocumentClock {
    override fun elapsedMs(): Long = 1_000L
  }
  private val table = DocumentIdTable(clock, Random(5))
  private val documents = LibraryMediaDocuments(library, DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS"), table, clock)
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)

  private fun TestScope.payloads(): DocumentDragPayloads {
    leases = DragLeases(this, graceMs = 600_000L)
    return DocumentDragPayloads(library, documents, authority, gate::decide, leases, nasProbeDeadlineMs = 5_000L)
  }

  private lateinit var leases: DragLeases

  private val phone = library.phoneIds()
  private val nas = library.nasOnlyIds()

  @Before
  fun setUp() {
    IntegrationCounters.reset()
    DragCounters.reset()
  }

  @After
  fun tearDown() {
    IntegrationCounters.reset()
    DragCounters.reset()
  }

  private fun request(ids: List<MediaId>, origin: DragOrigin = DragOrigin.LIBRARY, removeLocation: Boolean = false) = DragRequest(origin, ids, removeLocation)

  private fun ready(result: DragPrepared): DragPrepared.Ready = result as? DragPrepared.Ready ?: error("non e' pronto: $result")

  private fun uris(clip: ClipData): List<Uri> = (0 until clip.itemCount).map { clip.getItemAt(it).uri }

  private fun tokens(clip: ClipData): List<DocumentId> = uris(clip).map { DocumentId(it.lastPathSegment!!) }

  // --- Cosa esce --------------------------------------------------------------------------------------------------------

  @Test
  fun unTrascinamentoProntoHaSoloUriDelProviderConIdOpachiESolaLettura() = runTest {
    val chosen = phone.take(3) + nas.take(2)
    val result = ready(payloads().prepare(request(chosen)))
    assertThat(result.count).isEqualTo(5)
    val all = uris(result.clip)
    assertThat(all).hasSize(5)
    for (uri in all) {
      // Solo il nostro provider: mai un file://, mai un indirizzo del MediaStore, mai un FileProvider.
      assertThat(uri.scheme).isEqualTo("content")
      assertThat(uri.authority).isEqualTo(authority)
      assertThat(uri.pathSegments).hasSize(2)
      assertThat(uri.pathSegments[0]).isEqualTo("document")
      assertThat(uri.toString()).doesNotContain("media")
      assertThat(uri.toString()).doesNotContain("IMG_")
      for (id in chosen) assertThat(uri.toString()).doesNotContain(id.value)
    }
    assertThat(all.toSet()).hasSize(5)
    // Id opachi, consegnati, e ognuno apre proprio il suo media.
    for ((n, token) in tokens(result.clip).withIndex()) {
      assertThat(documents.isDelivered(token)).isTrue()
      assertThat(documents.entry(token)).isNotNull()
      assertThat(documents.entry(token)!!.isFolder).isFalse()
      assertThat(n).isAtLeast(0)
    }
    // Sola lettura sul singolo URI: mai scrittura, mai permessi persistenti o per prefisso.
    assertThat(result.flags).isEqualTo(DragFlags.ALLOWED)
    assertThat(result.flags and DragFlags.FORBIDDEN).isEqualTo(0)
    assertThat(result.toString()).doesNotContain(chosen.first().value)
    assertThat(DragCounters.get(DragCounter.OUT_READY)).isEqualTo(1L)
    assertThat(DragCounters.get(DragCounter.OUT_ITEMS)).isEqualTo(5L)
    assertThat(DragCounters.get(DragCounter.OUT_NAS_ONLY)).isEqualTo(2L)
  }

  @Test
  fun iTipiDelClipSonoQuelliDeiFile() = runTest {
    val chosen = listOf(phone.first(), nas.first())
    val result = ready(payloads().prepare(request(chosen)))
    val declared = (0 until result.clip.description.mimeTypeCount).map { result.clip.description.getMimeType(it) }
    val expected = library.fileInfos(chosen).values.map { it.mime }.toSet()
    assertThat(declared.toSet()).isEqualTo(expected)
    assertThat(result.clip.description.hasMimeType("image/*")).isTrue()
  }

  @Test
  fun unoSoloElementoEUnTrascinamento() = runTest {
    val result = ready(payloads().prepare(request(listOf(nas.first()), DragOrigin.VIEWER)))
    assertThat(result.count).isEqualTo(1)
    assertThat(result.clip.itemCount).isEqualTo(1)
  }

  // --- I tetti (E-X6, E-T12) -----------------------------------------------------------------------------------------------

  @Test
  fun oltreCentoElementiSiRifiutaPrimaDiCominciare() = runTest {
    val many = library.visibleIds.take(DragLimits.MAX_ITEMS + 1)
    val result = payloads().prepare(request(many))
    assertThat(result).isEqualTo(DragPrepared.Refused(DragRefusal.TOO_MANY))
    // Prima di cominciare: nessuna lettura del catalogo, nessun id.
    assertThat(library.fileInfoCalls.get()).isEqualTo(0)
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
    assertThat(DragCounters.get(DragCounter.OUT_REFUSED_TOO_MANY)).isEqualTo(1L)
  }

  @Test
  fun centoElementiPassano() = runTest {
    val exactly = library.visibleIds.take(DragLimits.MAX_ITEMS)
    val result = ready(payloads().prepare(request(exactly)))
    assertThat(result.count).isEqualTo(100)
    assertThat(table.size).isEqualTo(100)
  }

  @Test
  fun oltreDueGibSiRifiutaConIlNumeroDeiByteIlMotivoEPrimaDiCreareId() = runTest {
    val chosen = nas.take(3)
    library.sizeOverride[chosen[0]] = 1L shl 30
    library.sizeOverride[chosen[1]] = 1L shl 30
    library.sizeOverride[chosen[2]] = 1L
    assertThat(payloads().prepare(request(chosen))).isEqualTo(DragPrepared.Refused(DragRefusal.TOO_LARGE))
    assertThat(table.size).isEqualTo(0)
    assertThat(library.nasReachCalls.get()).isEqualTo(0)
    // Esattamente due GiB passano.
    library.sizeOverride[chosen[2]] = 0L
    assertThat(payloads().prepare(request(chosen))).isInstanceOf(DragPrepared.Ready::class.java)
  }

  @Test
  fun unMediaCheIlCatalogoNonConoscePiuFermaTutto() = runTest {
    val ghost = library.hiddenIds.first()
    val result = payloads().prepare(request(listOf(phone.first(), ghost)))
    assertThat(result).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(table.size).isEqualTo(0)
  }

  // --- Mai un nascosto (E-H11, E-T18) ----------------------------------------------------------------------------------------

  @Test
  fun unNascostoFermaTuttoIlTrascinamentoEIdNonNascono() = runTest {
    val chosen = phone.take(2) + nas.take(2)
    library.hide(setOf(chosen[1]))
    assertThat(payloads().prepare(request(chosen))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isAtLeast(1L)
    assertThat(DragCounters.get(DragCounter.OUT_REFUSED_HIDDEN)).isEqualTo(1L)
  }

  @Test
  fun unaPortaCheSbagliaNonFaUscireUnNascosto() = runTest {
    val secret = library.hiddenIds.first()
    library.leakHidden = true
    // La porta conosce il nascosto (riferimento, nome, byte): la vista di adesso no, e decide la vista.
    assertThat(library.fileInfos(listOf(secret))).isNotEmpty()
    assertThat(payloads().prepare(request(listOf(secret)))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(payloads().prepare(request(listOf(phone.first(), secret)))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(table.size).isEqualTo(0)
  }

  @Test
  fun unMediaNascostoDurantePreparazioneNonEsceNeLasciaId() = runTest {
    val chosen = phone.take(2) + nas.take(1)
    // Durante la prova del NAS (una sospensione) un altro dispositivo nasconde un media: l'ultimo controllo lo ferma, e gli id gia' creati non restano.
    library.beforeNasReach = { library.hide(setOf(chosen.first())) }
    assertThat(payloads().prepare(request(chosen))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
  }

  @Test
  fun unGruppoConUnNascostoNonSiTrascinaNemmenoDaiCompagni() = runTest {
    // Il nascosto e' contagioso (D36): il catalogo toglie dall'indice tutti i membri del gruppo; nessuno di loro e' trascinabile.
    val group = setOf(phone[0], phone[1], phone[2])
    library.hide(group)
    val handler = payloads()
    for (id in group) assertThat(handler.prepare(request(listOf(id)))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
    assertThat(table.size).isEqualTo(0)
  }

  @Test
  fun l_accessoParzialeNonConcedeCioCheLaVistaNonHa() = runTest {
    val allowed = phone.take(2).toSet()
    library.restrictView(allowed)
    val handler = payloads()
    assertThat(handler.prepare(request(listOf(phone[0])))).isInstanceOf(DragPrepared.Ready::class.java)
    assertThat(handler.prepare(request(listOf(phone[5])))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))
  }

  @Test
  fun nessunaOrigineNominaINascosti() {
    // E-T18: dai Nascosti non si trascina: non c'e' un'origine per loro (per tipo), quindi nessun modo di chiederlo.
    assertThat(DragOrigin.entries.map { it.code }).containsNoneOf("hidden", "hidden_page", "nascosti")
  }

  // --- La proprieta' --------------------------------------------------------------------------------------------------------

  @Test
  fun proprietaSuRichiesteACaso_unPronto_NonHaMaiUnNascosto() = runTest {
    val random = Random(20261102)
    val everything = library.visibleIds + library.hiddenIds
    val handler = payloads()
    var ready = 0
    var refused = 0
    repeat(300) { round ->
      library.showAll()
      library.leakHidden = round % 3 == 0
      // Nascosti dell'ultimo momento: alcuni visibili diventano nascosti prima della richiesta.
      val nowHidden = library.visibleIds.shuffled(random).take(random.nextInt(40)).toSet()
      if (nowHidden.isNotEmpty()) library.hide(nowHidden)
      val picks = everything.shuffled(random).take(1 + random.nextInt(8))
      val result = handler.prepare(request(picks, DragOrigin.entries[random.nextInt(DragOrigin.entries.size)]))
      val shouldPass = picks.none { it in nowHidden || it in library.hiddenIds }
      when (result) {
        is DragPrepared.Ready -> {
          ready++
          assertThat(shouldPass).isTrue()
          // Ogni URI porta a un media che e' nella vista di adesso, e nessuno e' un nascosto.
          val shown = tokens(result.clip).map { ((table.resolve(it) as DocumentResolution.Found).target as DocumentTarget.Media).id }
          assertThat(shown).containsExactlyElementsIn(picks).inOrder()
          for (token in tokens(result.clip)) assertThat(documents.entry(token)).isNotNull()
          result.lease.release()
        }
        is DragPrepared.Refused -> {
          refused++
          assertThat(shouldPass).isFalse()
          assertThat(result.reason).isEqualTo(DragRefusal.HIDDEN)
        }
      }
      assertThat(leases.count).isEqualTo(0)
    }
    assertThat(ready).isGreaterThan(20)
    assertThat(refused).isGreaterThan(20)
    assertThat(table.size).isEqualTo(0)
  }

  // --- Il NAS (E-T11) ------------------------------------------------------------------------------------------------------

  @Test
  fun ilNasNonRispondeDiceSubitoENonCreaId() = runTest {
    library.reach = NasReach.Unreachable(NasUnreachableReason.OFFLINE)
    val handler = payloads()
    assertThat(handler.prepare(request(nas.take(2)))).isEqualTo(DragPrepared.Refused(DragRefusal.NAS_UNREACHABLE))
    assertThat(handler.prepare(request(listOf(phone.first(), nas.first())))).isEqualTo(DragPrepared.Refused(DragRefusal.NAS_UNREACHABLE))
    assertThat(table.size).isEqualTo(0)
    // Una scelta tutta sul telefono non ha bisogno del NAS: passa, e il NAS non viene nemmeno interrogato.
    val before = library.nasReachCalls.get()
    assertThat(handler.prepare(request(phone.take(2)))).isInstanceOf(DragPrepared.Ready::class.java)
    assertThat(library.nasReachCalls.get()).isEqualTo(before)
  }

  @Test
  fun unNasCheNonFiniscePiuDiRispondereNonTieneInAttesaOltreLaScadenza() = runTest {
    library.beforeNasReach = { awaitCancellation() }
    val handler = payloads()
    val pending = async { handler.prepare(request(nas.take(1))) }
    runCurrent()
    assertThat(pending.isCompleted).isFalse()
    advanceTimeBy(4_900L)
    assertThat(pending.isCompleted).isFalse()
    advanceTimeBy(200L)
    assertThat(pending.await()).isEqualTo(DragPrepared.Refused(DragRefusal.NAS_UNREACHABLE))
    assertThat(table.size).isEqualTo(0)
  }

  // --- Il blocco dell'app (E-X7) ---------------------------------------------------------------------------------------------

  @Test
  fun conIlBloccoAttivoNonSiTrascinaEIlCatalogoNonSiTocca() = runTest {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    assertThat(payloads().prepare(request(phone.take(2)))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCKED))
    assertThat(library.fileInfoCalls.get()).isEqualTo(0)
    assertThat(table.size).isEqualTo(0)
    gate.decision = EntryDecision.NOT_READY
    assertThat(payloads().prepare(request(phone.take(2)))).isEqualTo(DragPrepared.Refused(DragRefusal.UNAVAILABLE))
    gate.decision = EntryDecision.LOADING
    assertThat(payloads().prepare(request(phone.take(2)))).isEqualTo(DragPrepared.Refused(DragRefusal.UNAVAILABLE))
  }

  @Test
  fun ilBloccoCheScattaDurantePreparazioneFermaTuttoPrimaDegliId() = runTest {
    library.beforeFileInfos = { gate.decision = EntryDecision.NEEDS_UNLOCK }
    assertThat(payloads().prepare(request(phone.take(2)))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCKED))
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
  }

  // --- L'annullamento (E-T17) ------------------------------------------------------------------------------------------------

  @Test
  fun annullareMentreSiPreparaNonLasciaIdNePrestiti() = runTest {
    library.beforeFileInfos = { awaitCancellation() }
    val handler = payloads()
    val pending = async(start = CoroutineStart.UNDISPATCHED) { handler.prepare(request(phone.take(3))) }
    runCurrent()
    pending.cancel()
    runCurrent()
    assertThat(pending.isCancelled).isTrue()
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
    library.beforeFileInfos = null
    library.beforeNasReach = { awaitCancellation() }
    val second = async(start = CoroutineStart.UNDISPATCHED) { handler.prepare(request(nas.take(1))) }
    runCurrent()
    second.cancel()
    runCurrent()
    assertThat(table.size).isEqualTo(0)
    assertThat(leases.count).isEqualTo(0)
  }

  // --- Il prestito (D20, E-T14) -----------------------------------------------------------------------------------------------

  @Test
  fun ilPrestitoRilasciatoTogliGliIdSubito() = runTest {
    val handler = payloads()
    val result = ready(handler.prepare(request(phone.take(2) + nas.take(1))))
    val tokens = tokens(result.clip)
    assertThat(leases.count).isEqualTo(1)
    result.lease.release()
    result.lease.release()
    assertThat(leases.count).isEqualTo(0)
    for (token in tokens) {
      assertThat(documents.isDelivered(token)).isFalse()
      assertThat(documents.entry(token)).isNull()
    }
    assertThat(table.size).isEqualTo(0)
    assertThat(DragCounters.get(DragCounter.OUT_DISCARDED)).isEqualTo(1L)
  }

  @Test
  fun unaRichiestaCheChiedeLaPosizioneTolta_ConLImpostazioneSpenta_SiRifiuta() = runTest {
    // Il provider servirebbe l'originale: un trascinamento che promette "senza posizione" non parte (fallire chiusi).
    assertThat(payloads().prepare(request(phone.take(1), removeLocation = true))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCATION_NOT_REMOVABLE))
    assertThat(table.size).isEqualTo(0)
    assertThat(library.fileInfoCalls.get()).isEqualTo(0)
  }
}
