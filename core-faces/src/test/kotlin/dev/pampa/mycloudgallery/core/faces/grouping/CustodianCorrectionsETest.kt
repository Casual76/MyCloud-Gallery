package dev.pampa.mycloudgallery.core.faces.grouping

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingOverCapException
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.noop.NoGoneContents
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Correzioni della Fase 8, pacchetto E (L343): oltre il tetto duro dei volti il passo del raggruppamento salta (R6) e **lo dice**: un numero, mai quanti volti.
 * Prima la `IllegalStateException` si confondeva con un archivio chiuso e la Diagnostica non vedeva niente.
 */
class CustodianCorrectionsETest {
  private val budget = 5.minutes

  /** Un archivio il cui elenco dei volti per il raggruppamento ([over] = oltre il tetto) lancia come `GroupFaceCache`. */
  private class CappedArchive(private val inner: InMemoryFacesArchive, var over: Boolean = false, var closed: Boolean = false) : FacesArchive by inner {
    override suspend fun groupFaces(): List<GroupFace> = when {
      over -> throw GroupingOverCapException()
      closed -> throw IllegalStateException("Archivio dei volti chiuso.")
      else -> inner.groupFaces()
    }
  }

  private fun custodian(archive: CappedArchive, nas: ClusterNas) = CustodianGrouping(
    archives = { archive },
    deviceId = "aaaa",
    leases = nas.leases("aaaa"),
    snapshots = nas.snapshots,
    publisher = NoFacesPublisher,
    gone = NoGoneContents,
  )

  @Test
  fun l343_ognipassoOltreIlTettoSiContaEDaGiroVuoto() = runTest {
    val nas = ClusterNas()
    val archive = CappedArchive(InMemoryFacesArchive(nas.generation))
    val grouping = custodian(archive, nas)
    assertThat(grouping.skipped.value).isEqualTo(0)

    archive.over = true
    assertThat(grouping.incremental(budget)).isEqualTo(GroupingReport.EMPTY)
    assertThat(grouping.nightly(budget)).isEqualTo(GroupingReport.EMPTY)

    // Senza la correzione il passo saltava in silenzio e il conto restava a zero.
    assertThat(grouping.skipped.value).isEqualTo(2)
    // Sotto il tetto il raggruppamento torna a lavorare e il conto non cresce.
    archive.over = false
    grouping.incremental(budget)
    assertThat(grouping.skipped.value).isEqualTo(2)
  }

  @Test
  fun unArchivioChiusoNonESaltatoPerIlTetto() = runTest {
    val nas = ClusterNas()
    val archive = CappedArchive(InMemoryFacesArchive(nas.generation), closed = true)
    val grouping = custodian(archive, nas)

    assertThat(grouping.incremental(budget)).isEqualTo(GroupingReport.EMPTY)

    // Un archivio chiuso (chiave distrutta, generazione cambiata) e' un'altra cosa: il giro salta senza dire "troppi volti".
    assertThat(grouping.skipped.value).isEqualTo(0)
  }

  @Test
  fun iltettoELaStessaIllegalStateExceptionDiSempre() {
    // Chi non conosce il tipo nuovo continua a trattarlo come un archivio chiuso (salta il giro senza cadere).
    assertThat(GroupingOverCapException()).isInstanceOf(IllegalStateException::class.java)
    // Mai numeri di volti nel messaggio (D37).
    assertThat(GroupingOverCapException().message).doesNotContainMatch("[0-9]")
  }
}
