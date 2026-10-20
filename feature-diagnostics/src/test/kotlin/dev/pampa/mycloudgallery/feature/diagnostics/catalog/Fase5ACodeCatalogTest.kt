package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * **Un test di catalogo per ogni codice** del backup (Fase 5A, P7; docs/fase5-progetto.md §2.6): ognuno dei 22 codici di
 * `BackupProblemCode` e le due voci di stato di `BackupDiagnostics` ha il suo `DiagCode` con gli edge case del documento, la sezione
 * «Backup», un titolo e un «cosa fare» (le due risorse esistono, non sono vuote) e nessuno stesso id di un altro codice.
 *
 * La tabella qui e' scritta a mano: [Fase5ACatalogTest] la confronta con quella del documento, e `DiagnosticsWiringTest` di `:app` con
 * `BackupProblemCode`.
 */
@RunWith(Parameterized::class)
class Fase5ACodeCatalogTest(private val id: String, private val edgeCases: Set<String>) {

  @Test
  fun ilCodiceECatalogatoConSezioneTitoloEGuida() {
    val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
    assertThat(entry.code.edgeCases).containsExactlyElementsIn(edgeCases)
    assertThat(entry.section).isEqualTo(DiagSection.BACKUP)
    assertThat(entry.titleRes).isNotEqualTo(0)
    assertThat(entry.guideRes).isNotEqualTo(0)
  }

  @Test
  fun ilCodiceHaUnIdentificativoUnicoEIlCatalogoLoTrovaPerIdEPerOggetto() {
    assertThat(DiagCatalog.entriesOf(DiagSection.BACKUP).count { it.code.id == id }).isEqualTo(1)
    assertThat(DiagCatalog.contains(DiagCode(id))).isTrue()
    assertThat(DiagCatalog.titleOf(DiagCode(id))).isNotEqualTo(0)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun codes(): List<Array<Any>> = listOf(
      arrayOf("BACKUP_NAS_UNREACHABLE", setOf("E-R5")),
      arrayOf("BACKUP_NAS_FULL", setOf("E-R9")),
      arrayOf("BACKUP_QUOTA", setOf("E-N13")),
      arrayOf("BACKUP_AUTH", setOf("E-R8")),
      arrayOf("BACKUP_READ_ONLY_AREA", setOf("E-N9", "E-I19")),
      arrayOf("BACKUP_NO_PRIVATE_AREA", setOf("E-O2")),
      arrayOf("BACKUP_MEDIA_PERMISSION", setOf("E-P15", "E-O4")),
      arrayOf("BACKUP_MEDIA_LOCATION", setOf("E-P21")),
      arrayOf("BACKUP_PARTIAL_ACCESS", setOf("E-P1")),
      arrayOf("BACKUP_CAP_REACHED", setOf("E-B10")),
      arrayOf("BACKUP_FROZEN", setOf("E-B19", "C11")),
      arrayOf("BACKUP_WRITES_STOPPED", setOf("E-B19")),
      arrayOf("BACKUP_TEST_ONLY", setOf("D12")),
      arrayOf("BACKUP_LOST_BEFORE_BACKUP", setOf("E-B4")),
      arrayOf("BACKUP_VERIFY_FAILED", setOf("E-B18", "E-B8")),
      arrayOf("BACKUP_UNREADABLE", setOf("D15", "E-M12")),
      arrayOf("BACKUP_COMMON_COPY_LOST", setOf("E-B13", "L44")),
      arrayOf("BACKUP_UNEXPECTED", setOf("R1")),
      arrayOf("BACKUP_SOURCE_CHANGED", setOf("E-E6")),
      arrayOf("BACKUP_PHONE_STORAGE_LOW", setOf("E-P3")),
      arrayOf("BACKUP_STALE", setOf("E-P15", "E-P2")),
      arrayOf("BACKUP_WAITING_UNLOCK", setOf("E-P13")),
      arrayOf("BACKUP_PROFILE_INACTIVE", setOf("E-P19")),
      arrayOf("BACKUP_STATE", emptySet<String>()),
      arrayOf("BACKUP_LAST_ROUND", emptySet<String>()),
      arrayOf("BACKUP_SIMILAR_COPIES", emptySet<String>()),
    )
  }
}
