package dev.pampa.mycloudgallery.feature.integrations.picker

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIssuer
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentsAuthority
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17; E-H1, E-X1, E-X4, E-X6): dalla scelta al risultato per l'altra app, con il telefono finto e il NAS finto. Le copie del
 * telefono escono come URI del MediaStore, i solo-NAS come URI del provider con un id opaco; i tetti, il NAS che non risponde, l'annullamento a
 * meta' e i nascosti sono provati qui, sulla funzione che prepara la consegna.
 */
@RunWith(AndroidJUnit4::class)
class PickerDeliveryTest {

  private val library = FakeIntegrationLibrary()
  private val labels = DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS")
  private val table = DocumentIdTable(random = Random(5))
  private val documents = LibraryMediaDocuments(library, labels, table)
  private val authority = "test.pkg.integrations.documents"

  @Before
  fun reset() = IntegrationCounters.reset()

  @After
  fun cleanup() = IntegrationCounters.reset()

  private fun request(configure: (PickerRequest) -> PickerRequest = { it }) = configure(
    PickerRequest(PickerAction.GET_CONTENT, images = true, videos = true, allowMultiple = true, localOnly = false, mimeFilters = listOf("*/*")),
  )

  private fun deliverer(request: PickerRequest = request(), issuer: DocumentIssuer? = documents, probeMs: Long = 5_000) =
    PickerDeliverer(library, issuer, request, authority, probeMs)

  private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

  private fun ready(delivery: PickerDelivery): PickerDelivery.Ready = delivery as PickerDelivery.Ready

  private fun refused(delivery: PickerDelivery): PickerNotice = (delivery as PickerDelivery.Refused).notice

  // --- Cosa esce ----------------------------------------------------------------------------------------------------------

  @Test
  fun leCopieDelTelefonoEscono_ComeUriDelMediaStore() {
    val ids = library.phoneIds().take(3).toSet()
    val result = ready(run { deliverer().prepare(ids) })
    assertThat(result.uris).hasSize(3)
    assertThat(result.nasOnly).isEqualTo(0)
    assertThat(result.minted).isEmpty()
    for (uri in result.uris) {
      assertThat(uri.scheme).isEqualTo("content")
      assertThat(uri.authority).isEqualTo("media")
      assertThat(uri.toString()).startsWith("content://media/external_primary/")
    }
    // Un media del telefono non crea ne' tocca nessun id: il provider non c'entra.
    assertThat(table.size).isEqualTo(0)
    assertThat(library.nasReachCalls.get()).isEqualTo(0)
  }

  @Test
  fun iSoloNasEscono_ComeUriDelProviderConUnIdOpaco() {
    val ids = library.nasOnlyIds().take(4).toSet()
    val result = ready(run { deliverer().prepare(ids) })
    assertThat(result.nasOnly).isEqualTo(4)
    assertThat(result.uris).hasSize(4)
    assertThat(result.minted).hasSize(4)
    val tokens = result.minted.map { it.value }.toSet()
    for (uri in result.uris) {
      assertThat(uri.scheme).isEqualTo("content")
      assertThat(uri.authority).isEqualTo(authority)
      assertThat(uri.pathSegments[0]).isEqualTo("document")
      assertThat(DocumentIds.isOpaque(uri.lastPathSegment!!)).isTrue()
      assertThat(tokens).contains(uri.lastPathSegment)
      // Nessun percorso, share, account o id interno nell'URI.
      val text = uri.toString()
      for (id in ids) assertThat(text).doesNotContain(id.value)
      assertThat(text).doesNotContain("IMG_")
      assertThat(text).doesNotContain("external_primary")
      assertThat(uri.query).isNull()
      assertThat(uri.fragment).isNull()
    }
    for (token in result.minted) assertThat(documents.isDelivered(token)).isTrue()
    assertThat(library.nasReachCalls.get()).isEqualTo(1)
  }

  @Test
  fun unaSceltaMistaEscePerIndiceDalPiuRecente() {
    val phone = library.phoneIds().take(2)
    val nas = library.nasOnlyIds().take(2)
    val result = ready(run { deliverer().prepare((phone + nas).toSet()) })
    assertThat(result.uris).hasSize(4)
    val positions = (phone + nas).map { library.visibleIds.indexOf(it) }.sorted()
    val expectedPhoneFirst = positions.map { index -> library.visibleIds[index] in phone }
    val actual = result.uris.map { it.authority == "media" }
    assertThat(actual).isEqualTo(expectedPhoneFirst)
  }

  @Test
  fun ilRisultatoPortaSoloIlPermessoDiLettura() {
    val result = ready(run { deliverer().prepare(library.phoneIds().take(2).toSet()) })
    val intent = PickerResults.intent(result.uris)
    assertThat(intent.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    assertThat(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).isEqualTo(0)
    assertThat(intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).isEqualTo(0)
    assertThat(intent.clipData!!.itemCount).isEqualTo(2)
    assertThat(intent.data).isNull()
    // Uno solo: anche in `data`.
    val single = PickerResults.intent(result.uris.take(1))
    assertThat(single.data).isEqualTo(result.uris.first())
    assertThat(single.clipData!!.itemCount).isEqualTo(1)
  }

  // --- Nascosti (E-H1) ------------------------------------------------------------------------------------------------

  @Test
  fun unMediaNascostoNonEsce_AnchePoicheLaGrigliaLAveva() {
    val ids = library.phoneIds().take(3)
    library.hide(setOf(ids[1]))
    val result = ready(run { deliverer().prepare(ids.toSet()) })
    assertThat(result.uris).hasSize(2)
    assertThat(result.skipped).isEqualTo(1)
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isEqualTo(1L)
  }

  @Test
  fun unMediaNascostoDuranteLeAttesePrimaDegliIdNonEsce() {
    val nas = library.nasOnlyIds().take(3)
    // Mentre il catalogo risponde, l'utente nasconde il secondo da un altro dispositivo.
    library.beforeFileInfos = { library.hide(setOf(nas[1])) }
    val result = ready(run { deliverer().prepare(nas.toSet()) })
    assertThat(result.uris).hasSize(2)
    assertThat(result.minted).hasSize(2)
    assertThat(result.skipped).isEqualTo(1)
    assertThat(documents.issue(nas[1])).isNull()
  }

  @Test
  fun lePortaCheSbagliaNonFaUscireIdDiMediaNonInVista() {
    library.leakHidden = true
    val result = run { deliverer().prepare(library.hiddenIds.toSet()) }
    assertThat(refused(result)).isEqualTo(PickerNotice.NothingAvailable)
    assertThat(table.size).isEqualTo(0)
  }

  // --- Tetti (E-X6) -----------------------------------------------------------------------------------------------------

  @Test
  fun oltreCentoSoloNasRifiutoMotivatoESenzaId_E_X6() {
    val ids = library.nasOnlyIds()
    assertThat(ids.size).isGreaterThan(DocumentPolicy.MAX_NAS_ONLY_ITEMS + 1)
    val notice = refused(run { deliverer().prepare(ids.take(DocumentPolicy.MAX_NAS_ONLY_ITEMS + 1).toSet()) })
    assertThat(notice).isEqualTo(PickerNotice.TooManyNasOnly(DocumentPolicy.MAX_NAS_ONLY_ITEMS + 1, ids.take(101).sumOf { library.infoOf(it)!!.size }))
    assertThat(table.size).isEqualTo(0)
    assertThat(library.nasReachCalls.get()).isEqualTo(0)
    // Esattamente cento: passa.
    val ok = ready(run { deliverer().prepare(ids.take(DocumentPolicy.MAX_NAS_ONLY_ITEMS).toSet()) })
    assertThat(ok.uris).hasSize(DocumentPolicy.MAX_NAS_ONLY_ITEMS)
  }

  @Test
  fun oltreDueGibDiSoloNasRifiutoMotivato_E_X6() {
    val ids = library.nasOnlyIds().take(3)
    ids.forEach { library.sizeOverride[it] = 800L * 1024 * 1024 } // 3 x 800 MiB = 2,34 GiB, senza i byte
    val notice = refused(run { deliverer().prepare(ids.toSet()) }) as PickerNotice.TooManyNasOnly
    assertThat(notice.count).isEqualTo(3)
    assertThat(notice.bytes).isGreaterThan(DocumentPolicy.MAX_NAS_ONLY_BYTES)
    assertThat(table.size).isEqualTo(0)
    // Due soli (1,56 GiB): passano.
    val two = ready(run { deliverer().prepare(ids.take(2).toSet()) })
    assertThat(two.uris).hasSize(2)
  }

  @Test
  fun iTettiNonContanoLeCopieDelTelefono() {
    val phone = library.phoneIds().take(250)
    val nas = library.nasOnlyIds().take(10)
    val result = ready(run { deliverer().prepare((phone + nas).toSet()) })
    assertThat(result.uris).hasSize(260)
    // Ma in tutto non oltre il massimo.
    val tooMany = refused(run { deliverer().prepare((library.phoneIds() + library.nasOnlyIds()).take(DocumentPolicy.MAX_PICKED_ITEMS + 1).toSet()) })
    assertThat(tooMany).isEqualTo(PickerNotice.TooMany(DocumentPolicy.MAX_PICKED_ITEMS))
  }

  // --- NAS irraggiungibile (E-X1) ---------------------------------------------------------------------------------------

  @Test
  fun ilNasNonRispondeDettoSubitoConIlMotivoPrimaDiCrearGliId_E_X1() {
    for (reason in NasUnreachableReason.entries) {
      library.reach = NasReach.Unreachable(reason)
      val notice = refused(run { deliverer().prepare(library.nasOnlyIds().take(2).toSet()) })
      assertThat(notice).isEqualTo(PickerNotice.NasUnreachable(reason))
    }
    assertThat(table.size).isEqualTo(0)
    assertThat(IntegrationCounters.get(IntegrationCounter.PICKER_NAS_UNREACHABLE)).isEqualTo(NasUnreachableReason.entries.size.toLong())
  }

  @Test
  fun ilNasCheNonRispondeMaiNonLasciaInAttesa_E_X1() {
    library.beforeNasReach = { awaitCancellation() }
    val started = System.nanoTime()
    val notice = refused(run { deliverer(probeMs = 150).prepare(library.nasOnlyIds().take(2).toSet()) })
    assertThat(notice).isEqualTo(PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE))
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
    assertThat(table.size).isEqualTo(0)
  }

  @Test
  fun leCopieDelTelefonoSiConsegnanoAncheSenzaNas() {
    library.reach = NasReach.Unreachable(NasUnreachableReason.OFFLINE)
    val result = ready(run { deliverer().prepare(library.phoneIds().take(3).toSet()) })
    assertThat(result.uris).hasSize(3)
    assertThat(library.nasReachCalls.get()).isEqualTo(0)
  }

  @Test
  fun senzaChiConsegnaISoloNasNonSiConsegnanoMaiAlMomentoGiusto() {
    val notice = refused(run { deliverer(issuer = null).prepare(library.nasOnlyIds().take(2).toSet()) })
    assertThat(notice).isEqualTo(PickerNotice.NasUnreachable(NasUnreachableReason.UNAVAILABLE))
  }

  // --- Annullamento a meta' (E-X4) --------------------------------------------------------------------------------------

  @Test
  fun annullareMentreSiChiedonoLeInformazioniNonLasciaNiente_E_X4() {
    val gate = CompletableDeferred<Unit>()
    val reached = CompletableDeferred<Unit>()
    library.beforeFileInfos = {
      reached.complete(Unit)
      gate.await()
    }
    runBlocking {
      val job = async(start = CoroutineStart.UNDISPATCHED) { deliverer().prepare(library.nasOnlyIds().take(3).toSet()) }
      withTimeout(5_000) { reached.await() }
      job.cancel()
      runCatching { job.await() }
      assertThat(job.isCancelled).isTrue()
    }
    assertThat(table.size).isEqualTo(0)
    assertThat(library.nasReachCalls.get()).isEqualTo(0)
  }

  @Test
  fun annullareMentreSiProvaIlNasNonLasciaNiente_E_X4() {
    val reached = CompletableDeferred<Unit>()
    library.beforeNasReach = {
      reached.complete(Unit)
      awaitCancellation()
    }
    runBlocking {
      val job = async(start = CoroutineStart.UNDISPATCHED) { deliverer().prepare(library.nasOnlyIds().take(3).toSet()) }
      withTimeout(5_000) { reached.await() }
      yield()
      job.cancel()
      runCatching { job.await() }
      assertThat(job.isCancelled).isTrue()
    }
    assertThat(table.size).isEqualTo(0)
    assertThat(library.nasReaders).isEmpty()
  }

  @Test
  fun seQualcosaFallisceDopoCheGliIdSonoNatiGliIdSiRevocano_E_X4() {
    val minted = mutableListOf<DocumentId>()
    val exploding = object : DocumentIssuer by documents {
      override fun issue(id: MediaId): DocumentId? {
        if (minted.size == 2) throw IllegalStateException("guasto a meta'")
        return documents.issue(id)?.also { minted += it }
      }
    }
    val failure = runCatching { run { deliverer(issuer = exploding).prepare(library.nasOnlyIds().take(4).toSet()) } }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(minted).hasSize(2)
    for (token in minted) assertThat(documents.isDelivered(token)).isFalse()
    assertThat(table.size).isEqualTo(0)
  }

  // --- Tipi richiesti ---------------------------------------------------------------------------------------------------

  @Test
  fun untipoPrecisoRichiestoEscludeGliAltri() {
    val jpegOnly = request { it.copy(videos = false, mimeFilters = listOf("image/jpeg")) }
    val ids = library.phoneIds().take(40)
    val result = ready(run { deliverer(jpegOnly).prepare(ids.toSet()) })
    val expected = ids.count { library.infoOf(it)!!.mime == "image/jpeg" }
    assertThat(result.uris).hasSize(expected)
    assertThat(result.skipped).isEqualTo(ids.size - expected)
  }

  @Test
  fun unUriVuotoDelTelefonoNonSiConsegna() {
    val empty = object : dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary by library {
      override fun phoneUri(ref: dev.pampa.mycloudgallery.core.model.MediaRef.Phone): Uri = Uri.EMPTY
    }
    val notice = refused(run { PickerDeliverer(empty, documents, request(), authority).prepare(library.phoneIds().take(2).toSet()) })
    assertThat(notice).isEqualTo(PickerNotice.NothingAvailable)
  }

  @Test
  fun nessunDatoDellUtenteNelleStringheDiDiagnostica() {
    val ready = ready(run { deliverer().prepare((library.phoneIds().take(1) + library.nasOnlyIds().take(1)).toSet()) })
    assertThat(ready.toString()).matches("Ready\\(\\d+, skipped=\\d+, nasOnly=\\d+\\)")
    assertThat(PickerNotice.TooManyNasOnly(3, 5L).toString()).doesNotContain("IMG_")
    assertThat(IntegrationCounters.snapshot().keys).containsAtLeast("picker_delivered", "picker_delivered_nas_only", "picker_skipped")
    assertThat(DocumentsAuthority.SUFFIX).isEqualTo(".integrations.documents")
  }
}
