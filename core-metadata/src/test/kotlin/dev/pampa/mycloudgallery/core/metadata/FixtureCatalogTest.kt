package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tutto il catalogo di `tools/fixtures` (date, nomi, formati, orientamenti, tratti, motion photo, file rovinati)
 * attraverso l'estrattore vero, contro i valori attesi scritti a mano dalla specifica. Una sola prova per sezione, cosi' il
 * rapporto dice quale file e quale campo.
 */
class FixtureCatalogTest {

  private fun runSection(section: String) = runTest {
    val fixtures = FixtureCatalog.all().filter { it.section == section }
    assertThat(fixtures).isNotEmpty()
    val failures = fixtures.flatMap { f -> f.mismatches(f.extract()).map { "${f.name}: $it" } }
    assertThat(failures).isEmpty()
  }

  @Test
  fun dateDallExif() = runSection(FixtureCatalog.DATA)

  @Test
  fun dateAssurdeSiScartano() = runSection(FixtureCatalog.ASSURDE)

  @Test
  fun dateESorgentiDaiNomiDeiFile() = runSection(FixtureCatalog.NOMI)

  @Test
  fun formati() = runSection(FixtureCatalog.FORMATI)

  @Test
  fun ottoOrientamenti() = runSection(FixtureCatalog.ORIENTAMENTI)

  @Test
  fun tratti() = runSection(FixtureCatalog.TRATTI)

  @Test
  fun motionPhoto() = runSection(FixtureCatalog.MOTION)

  @Test
  fun fileRovinatiTroncatiEFalsi() = runSection(FixtureCatalog.ROVINATI)

  @Test
  fun ilCatalogoCopreTutteLeSezioni() {
    val sections = FixtureCatalog.all().map { it.section }.toSet()
    assertThat(sections).containsExactly(
      FixtureCatalog.DATA, FixtureCatalog.ASSURDE, FixtureCatalog.NOMI, FixtureCatalog.FORMATI,
      FixtureCatalog.ORIENTAMENTI, FixtureCatalog.TRATTI, FixtureCatalog.MOTION, FixtureCatalog.ROVINATI,
    )
    // Nomi unici: nel set per il telefono stanno tutti nella stessa cartella.
    val names = FixtureCatalog.all().map { it.name } + FixtureCatalog.groupSets().flatMap { s -> s.members.map { it.name } }
    assertThat(names).containsNoDuplicates()
  }
}
