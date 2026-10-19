package dev.pampa.mycloudgallery.feature.integrations

import android.database.Cursor
import android.os.Bundle
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerAction
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerDelivery
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerDeliverer
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerItems
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerRequest
import dev.pampa.mycloudgallery.feature.integrations.provider.CountingHandlers
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.FakeProxyOpener
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.MediaDocumentsProvider
import dev.pampa.mycloudgallery.feature.integrations.provider.ProviderTestSupport
import dev.pampa.mycloudgallery.feature.integrations.provider.ProviderTuning
import dev.pampa.mycloudgallery.feature.integrations.provider.ScriptedCaller
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17; riga P10 del §4.4; E-H1, E-L11): **i media nascosti non escono mai, da nessuna delle due vie**: il selettore
 * (`PickerActivity`) e il provider dei documenti (`MediaDocumentsProvider`). Vale anche il nascosto contagioso delle copie simili (D36): una
 * foto nascosta nasconde tutto il suo gruppo, e la stessa foto non deve ricomparire dalla porta di una sua copia.
 *
 * Lo scenario e' lo stesso per le due vie, con la porta della Libreria che **sbaglia apposta** (restituisce nascosti, nomi, riferimenti e byte
 * di cio' che l'indice non ha): a tenerli fuori deve essere il codice del selettore e del provider, non la fiducia nella porta.
 */
@RunWith(AndroidJUnit4::class)
class HiddenNeverLeavesTest {

  private val library = FakeIntegrationLibrary().also { it.leakHidden = true }
  private val labels = DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS")
  private val documents = LibraryMediaDocuments(library, labels, DocumentIdTable(random = java.util.Random(12)))
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val caller = ScriptedCaller().also { it.documentsUi() }
  private val opener = FakeProxyOpener()

  /** Un gruppo di copie simili in cui una e' stata nascosta: il catalogo toglie dall'indice tutti i membri insieme (D36, E-L11). */
  private val group: List<MediaId> = library.nasOnlyIndices.filter { it % 2 == 0 }.take(3).map { library.visibleIds[it] }

  /** Tutto cio' che non puo' uscire: i nascosti e il gruppo contagiato. */
  private val forbidden: Set<MediaId> = library.hiddenIds.toSet() + group.toSet()

  private fun nameOf(id: MediaId): String =
    library.visibleIds.indexOf(id).takeIf { it >= 0 }?.let { "IMG_%05d.".format(it) } ?: "SEGRETO_"

  @Before
  fun setUp() {
    library.hide(group.toSet())
    IntegrationPorts.install(gate, documents)
    ProviderTuning.reset()
    ProviderTuning.callerChecks = { caller }
    ProviderTuning.proxyOpener = { opener }
    ProviderTuning.handlers = CountingHandlers()
  }

  @After
  fun tearDown() {
    IntegrationPorts.reset()
    ProviderTuning.reset()
    opener.cleanup()
  }

  // --- Via 1: il selettore ---------------------------------------------------------------------------------------------------

  private val request = PickerRequest(PickerAction.GET_CONTENT, images = true, videos = true, allowMultiple = true, localOnly = false, mimeFilters = listOf("*/*"))

  @Test
  fun viaSelettore_LaGrigliaNonHaNeNascostiNeIlGruppoContagiato() {
    val items = PickerItems.of(library.items.view.value.snapshot, request)
    val shown = (0 until items.size).map { items.idAt(it) }.toSet()
    assertThat(shown).containsNoneIn(forbidden)
    assertThat(shown.size).isEqualTo(library.visibleIds.size - group.size)
  }

  @Test
  fun viaSelettore_AncheSeSiProvaAScegliereUnNascostoNonEsce() {
    val everything: Set<MediaId> = (library.visibleIds.take(40) + forbidden).toSet()
    val delivery = runBlocking { PickerDeliverer(library, documents, request, "t.p.integrations.documents").prepare(everything) } as PickerDelivery.Ready
    val expectedVisible = everything - forbidden
    assertThat(delivery.uris).hasSize(expectedVisible.size)
    assertThat(delivery.skipped).isEqualTo(forbidden.size)
    // Ogni URI dei solo-NAS risolve a un media visibile: nessun nascosto dietro un id.
    for (uri in delivery.uris.filter { it.authority != "media" }) {
      val token = dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId(uri.lastPathSegment!!)
      val entry = runBlocking { documents.entry(token) }!!
      assertThat(entry.displayName).startsWith("IMG_")
      assertThat(forbidden.map { nameOf(it) }.filter { it != "SEGRETO_" }.any { entry.displayName.startsWith(it) }).isFalse()
    }
    // Gli URI del telefono non portano l'identita' di nessun nascosto.
    for (uri in delivery.uris) for (id in forbidden) assertThat(uri.toString()).doesNotContain(id.value)
  }

  @Test
  fun viaSelettore_SoloUnNascostoScelto_NienteDaConsegnare() {
    val delivery = runBlocking { PickerDeliverer(library, documents, request, "t.p.integrations.documents").prepare(library.hiddenIds.take(3).toSet()) }
    assertThat(delivery).isInstanceOf(PickerDelivery.Refused::class.java)
  }

  // --- Via 2: il provider dei documenti ---------------------------------------------------------------------------------------

  private class Row(val id: String, val name: String, val isDir: Boolean)

  private fun rows(cursor: Cursor): List<Row> = buildList {
    val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
    val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
    val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
    while (cursor.moveToNext()) add(Row(cursor.getString(id), cursor.getString(name), cursor.getString(mime) == Document.MIME_TYPE_DIR))
  }

  /** Tutto cio' che un selettore di documenti puo' vedere sfogliando la radice, a ogni profondita'. */
  private fun everythingBrowsable(provider: MediaDocumentsProvider): List<Row> {
    val roots = provider.query(ProviderTestSupport.rootsUri(), null, Bundle.EMPTY, null)!!.use {
      it.moveToFirst()
      it.getString(it.getColumnIndexOrThrow(android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID))
    }
    val seen = LinkedHashMap<String, Row>()
    val queue = ArrayDeque(listOf(roots))
    while (queue.isNotEmpty()) {
      val parent = queue.removeFirst()
      val children = provider.query(ProviderTestSupport.childrenUri(parent), null, Bundle.EMPTY, null)!!.use { rows(it) }
      for (child in children) {
        if (seen.put(child.id, child) == null && child.isDir) queue.addLast(child.id)
      }
    }
    return seen.values.toList()
  }

  @Test
  fun viaProvider_NelleCartelleNonCeNeUnNascostoNeIlGruppoContagiato() {
    val provider = ProviderTestSupport.provider()
    val browsable = everythingBrowsable(provider)
    assertThat(browsable.count { !it.isDir }).isGreaterThan(50)
    val forbiddenPrefixes = group.map { nameOf(it) }
    for (row in browsable) {
      assertThat(row.name).doesNotContain("SEGRETO_")
      for (prefix in forbiddenPrefixes) assertThat(row.name).doesNotMatch("^" + Regex.escape(prefix) + ".*")
    }
    // E tutto cio' che si vede si apre; ma nessun nascosto ha un id da aprire.
    for (id in forbidden) assertThat(documents.issue(id)).isNull()
  }

  @Test
  fun viaProvider_UnMediaNascostoDopoAverloVistoNonSiApreNePiuSiDescrive() {
    val provider = ProviderTestSupport.provider()
    val file = everythingBrowsable(provider).first { !it.isDir }
    provider.openDocument(file.id, "r", null).close()
    provider.queryDocument(file.id, null).use { assertThat(rows(it)).hasSize(1) }
    // L'utente lo nasconde da un altro dispositivo: l'id che l'altra app ha in mano smette di valere, anche se la porta lo conosce ancora.
    val hiddenNow = library.visibleIds.first { id -> library.infoOf(id)?.displayName == file.name }
    library.hide(group.toSet() + hiddenNow)
    org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) { provider.queryDocument(file.id, null) }
    org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) { provider.openDocument(file.id, "r", null) }
    // Un id mai emesso con la forma giusta non da' piu' di un id sparito: nessun oracolo.
    org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) { provider.openDocument("aaaaaaaaaaaaaaaaaaaaaaaa", "r", null) }
    assertThat(DocumentIds.isOpaque(file.id)).isTrue()
  }

  @Test
  fun leDueVieDannoLaStessaRispostaSuiNascosti() {
    val items = PickerItems.of(library.items.view.value.snapshot, request)
    val pickerNames = (0 until items.size).map { library.infoOf(items.idAt(it))!!.displayName }.toSet()
    val provider = ProviderTestSupport.provider()
    val providerNames = everythingBrowsable(provider).filter { !it.isDir }.map { it.name }.toSet()
    // Il provider mostra un sottoinsieme dei media del selettore (i recenti piu' le cartelle), mai qualcosa in piu'.
    assertThat(pickerNames).containsAtLeastElementsIn(providerNames)
  }
}
