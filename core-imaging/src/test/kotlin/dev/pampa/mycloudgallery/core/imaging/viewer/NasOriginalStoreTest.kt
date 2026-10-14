package dev.pampa.mycloudgallery.core.imaging.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import java.io.File
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** I temporanei degli originali del NAS: mai a meta', tetto, versioni, nessun percorso sul disco (P8, D19). */
class NasOriginalStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val dir: File get() = File(folder.root, "viewer-nas")

  private fun store(max: Long = 10_000, free: Long = Long.MAX_VALUE) = NasOriginalStore(dir, max) { free }

  private fun NasOriginalStore.put(id: Long, size: Int, modified: Long = 1_000, fill: Byte = 7): File {
    val writer = begin(id, NasStamp(size.toLong(), modified), size.toLong())
    writer.write(ByteArray(size) { fill }, 0, size)
    return writer.commit()
  }

  @Test
  fun unOriginaleCompletoSiRitrovaEUnoAMetaNo() {
    val store = store()
    val writer = store.begin(1, NasStamp(100, 5), 100)
    writer.write(ByteArray(60), 0, 60)

    assertThat(store.get(1)).isNull()
    assertThat(dir.listFiles()!!.map { it.name }).containsExactly("1.100.5.part")

    writer.write(ByteArray(40), 0, 40)
    val done = writer.commit()

    assertThat(store.get(1)).isEqualTo(done)
    assertThat(done.length()).isEqualTo(100)
    assertThat(dir.listFiles()!!.map { it.name }).containsExactly("1.100.5.orig")
    assertThat(store.totalBytes).isEqualTo(100)
  }

  @Test
  fun unaScritturaCortaOLungaNonDiventaMaiUnOriginale() {
    val store = store()
    val writer = store.begin(2, NasStamp(100, 5), 100)
    writer.write(ByteArray(99), 0, 99)

    assertThrows(IOException::class.java) { writer.commit() }

    assertThat(store.get(2)).isNull()
    assertThat(dir.listFiles()!!).isEmpty()
  }

  @Test
  fun annullareTogliIlPartEPuoEssereRipetuto() {
    val store = store()
    val writer = store.begin(3, NasStamp(100, 5), 100)
    writer.write(ByteArray(10), 0, 10)

    writer.abort()
    writer.abort()

    assertThat(dir.listFiles()!!).isEmpty()
    assertThat(store.get(3)).isNull()
  }

  @Test
  fun unProcessoMortoALasciatoDeiPartEZeroSiTolgonoAllApertura() {
    dir.mkdirs()
    File(dir, "9.50.1.part").writeBytes(ByteArray(20))
    File(dir, "8.50.1.orig").writeBytes(ByteArray(50))
    // Un .orig della dimensione sbagliata non e' un originale.
    File(dir, "7.50.1.orig").writeBytes(ByteArray(10))

    val store = store()

    assertThat(File(dir, "9.50.1.part").exists()).isFalse()
    assertThat(File(dir, "7.50.1.orig").exists()).isFalse()
    assertThat(store.get(8)).isNotNull()
    assertThat(store.totalBytes).isEqualTo(50)
  }

  @Test
  fun unFileModificatoDalPcNonRiusaIlTemporaneoVecchio() {
    val store = store()
    store.put(4, size = 100, modified = 1_000)

    assertThat(store.get(4, expected = NasStamp(100, 1_000))).isNotNull()
    // Stessa dimensione, data nuova: e' un altro file.
    assertThat(store.get(4, expected = NasStamp(100, 2_000))).isNull()
    assertThat(dir.listFiles()!!).isEmpty()
    assertThat(store.totalBytes).isEqualTo(0)
  }

  @Test
  fun seIlCatalogoENonHaAncoraRivistoIlFileIlTemporaneoPiuRecenteEQuelloGiusto() {
    val store = store()
    store.put(4, size = 120, modified = 2_000)

    // Il catalogo ricorda ancora la data vecchia (e un'altra dimensione): il temporaneo e' piu' recente, si tiene.
    assertThat(store.get(4, expected = NasStamp(100, 1_000))).isNotNull()
    assertThat(store.count).isEqualTo(1)
    // A pari data, un altro file e' un altro file.
    assertThat(store.get(4, expected = NasStamp(999, 2_000))).isNull()
  }

  @Test
  fun unaVersioneNuovaSostituisceLaVecchia() {
    val store = store()
    store.put(5, size = 100, modified = 1_000)
    store.put(5, size = 120, modified = 2_000)

    assertThat(dir.listFiles()!!.map { it.name }).containsExactly("5.120.2000.orig")
    assertThat(store.count).isEqualTo(1)
    assertThat(store.totalBytes).isEqualTo(120)
  }

  @Test
  fun oltreIlTettoEscePrimaIlMenoUsatoENonQuelloAppenaScritto() {
    val store = store(max = 1_000)
    store.put(1, 400)
    store.put(2, 400)
    // Usare l'1 lo rende piu' recente del 2.
    assertThat(store.get(1)).isNotNull()

    store.put(3, 400)

    assertThat(store.get(2)).isNull()
    assertThat(store.get(1)).isNotNull()
    assertThat(store.get(3)).isNotNull()
    assertThat(store.totalBytes).isAtMost(1_000)
  }

  @Test
  fun senzaPostoNonSiComincia() {
    val store = store(max = 100_000, free = 1_000)

    val e = assertThrows(IOException::class.java) { store.begin(1, NasStamp(500, 1), 500) }

    assertThat(NasOriginalStore.isNoSpace(e)).isTrue()
    assertThat(dir.listFiles()!!).isEmpty()
  }

  @Test
  fun unOriginaleOltreIlTettoNonSiComincia() {
    val store = store(max = 1_000)

    assertThrows(IOException::class.java) { store.begin(1, NasStamp(2_000, 1), 2_000) }
  }

  @Test
  fun iNomiNonContengonoPercorsiDelNas() {
    val store = store()
    store.put(42, 10, modified = 123)

    assertThat(dir.listFiles()!!.single().name).matches("""\d+\.\d+\.\d+\.orig""")
  }

  @Test
  fun clearTogliTutto() {
    val store = store()
    store.put(1, 10)
    store.put(2, 10)
    // Il .part di un processo morto (nessuno lo tiene aperto): clear lo toglie come gli originali.
    File(dir, "3.10.1.part").writeBytes(ByteArray(3))

    store.clear()

    assertThat(dir.listFiles()!!).isEmpty()
    assertThat(store.count).isEqualTo(0)
    assertThat(store.totalBytes).isEqualTo(0)
  }
}
