package dev.pampa.mycloudgallery.feature.integrations

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.noop.NoEntryGate
import dev.pampa.mycloudgallery.feature.integrations.noop.NoMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import dev.pampa.mycloudgallery.feature.integrations.testing.InMemoryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.testing.IntegrationSamples
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5B (P0-B, D23, C17, E-X1, E-X7): le porte che i componenti d'ingresso leggono. Senza installazione valgono i null-object (nessun ingresso,
 * nessun documento); gli id dei documenti sono opachi; il fake apre a intervalli e un NAS spento non lascia in attesa.
 */
class IntegrationPortsTest {

  @After
  fun restore() = IntegrationPorts.reset()

  @Test
  fun senzaInstallazioneNienteSiOffre() = runTest {
    IntegrationPorts.reset()
    assertThat(IntegrationPorts.entryGate).isSameInstanceAs(NoEntryGate)
    assertThat(IntegrationPorts.entryGate.decide()).isEqualTo(EntryDecision.NOT_READY)
    assertThat(IntegrationPorts.documents).isSameInstanceAs(NoMediaDocuments)
    assertThat(IntegrationPorts.documents.roots()).isEmpty()
    assertThat(IntegrationPorts.documents.entry(DocumentId("abcdefgh"))).isNull()
    assertThat(IntegrationPorts.documents.open(DocumentId("abcdefgh"))).isNull()
  }

  @Test
  fun leDuePorteSiInstallanoERimettonoIlNullObject() {
    val gate = FakeEntryGate(EntryDecision.NEEDS_UNLOCK)
    val documents = InMemoryMediaDocuments()
    IntegrationPorts.install(gate, documents)
    assertThat(IntegrationPorts.entryGate.decide()).isEqualTo(EntryDecision.NEEDS_UNLOCK)
    assertThat(gate.asked).isEqualTo(1)
    assertThat(IntegrationPorts.documents).isSameInstanceAs(documents)
    IntegrationPorts.install(null, null)
    assertThat(IntegrationPorts.entryGate).isSameInstanceAs(NoEntryGate)
    assertThat(IntegrationPorts.documents).isSameInstanceAs(NoMediaDocuments)
  }

  @Test
  fun gliIdDeiDocumentiSonoOpachi() {
    assertThat(DocumentIds.isOpaque("aB3-_xY9qZ")).isTrue()
    for (hostile in listOf("", "corto", "../../etc/passwd", "a/b/c/d/e/f/g", "Pictures/IMG_1.jpg", "nome con spazi", "x".repeat(65), "IMG_1.jpg.jpg")) {
      assertThat(DocumentIds.isOpaque(hostile)).isFalse()
      assertThrows(IllegalArgumentException::class.java) { DocumentId(hostile) }
    }
    // Il toString non mostra l'id: niente nei log.
    assertThat(DocumentId("aB3-_xY9qZ").toString()).doesNotContain("aB3")
  }

  @Test
  fun ilProviderDiProvaApreAIntervalliEUnNasSpentoNonLasciaInAttesa() = runTest {
    val doc = IntegrationSamples.document("doc-0001-x", size = 100)
    val documents = InMemoryMediaDocuments(listOf(doc))
    assertThat(documents.roots().single().rootDocument).isEqualTo(documents.root.rootDocument)
    assertThat(documents.children(documents.root.rootDocument, 10).map { it.id }).containsExactly(doc.entry.id)
    // Una cartella non legge mai i byte.
    assertThat(documents.readCalls).isEqualTo(0)

    val reader = documents.open(doc.entry.id)!!
    val buffer = ByteArray(30)
    assertThat(reader.read(90, buffer, 0, 30)).isEqualTo(10)
    assertThat(reader.read(100, buffer, 0, 30)).isEqualTo(0)
    assertThat(buffer.take(10)).isEqualTo((90..99).map { it.toByte() })
    reader.close()

    // E-X1: il NAS non risponde, errore subito.
    documents.openFails = true
    assertThat(documents.open(doc.entry.id)).isNull()
  }
}
