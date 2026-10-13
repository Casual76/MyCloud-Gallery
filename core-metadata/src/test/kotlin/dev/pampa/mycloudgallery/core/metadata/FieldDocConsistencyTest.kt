package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import dev.pampa.mycloudgallery.fixtures.FixtureDocs
import java.io.File
import org.junit.Test

/**
 * `docs/fase3-campo.md` riporta i valori attesi del set di prova per il telefono (F2): qui si verifica che siano gli stessi
 * che i test controllano. Le tabelle fra i marcatori del documento sono generate dal catalogo di `tools/fixtures`
 * (`generate.ps1 -Doc <file>`): se si cambia un valore atteso, si rigenerano e si incollano.
 */
class FieldDocConsistencyTest {

  private val doc = File("../docs/fase3-campo.md")

  @Test
  fun leTabelleDelDocumentoSonoQuelleGeneratePerIlCatalogo() {
    assertThat(doc.isFile).isTrue()
    val text = doc.readText(Charsets.UTF_8).replace("\r\n", "\n")
    val begin = text.indexOf(FixtureDocs.BEGIN)
    val end = text.indexOf(FixtureDocs.END)
    assertThat(begin).isAtLeast(0)
    assertThat(end).isGreaterThan(begin)
    val inDoc = text.substring(begin, end + FixtureDocs.END.length)
    assertThat(inDoc).isEqualTo(FixtureDocs.markdown())
  }

  @Test
  fun ognunDeiFileCheVaSulTelefonoHaUnaRigaNelDocumento() {
    val text = doc.readText(Charsets.UTF_8)
    for (f in FixtureCatalog.all().filter { it.onDevice }) assertThat(text).contains("`${f.name}`")
    for (set in FixtureCatalog.groupSets()) for (m in set.members) assertThat(text).contains("`${m.name}`")
    assertThat(text).contains("grande-200mp.jpg")
  }
}
