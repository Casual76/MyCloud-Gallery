package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Il repository con i media del NAS (Fase 4, P7): la patch dal flusso dei cambiamenti e' **la stessa cosa** di una
 * ricostruzione anche quando i media vanno e vengono dal NAS e il riferimento passa dal telefono al NAS e ritorno
 * (E-P22), e l'istantanea `MCGT2` riporta tutto al riavvio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasRepositoryTest {

  private val dir: File = Files.createTempDirectory("nasrepo").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun id(r: Random) = UUID(r.nextLong(), r.nextLong()).toString()

  private fun nas(r: Random, mime: String? = "image/jpeg") = MediaRef.Nas(locationId = 1L + r.nextInt(1_000_000), contentKey = r.nextLong(), mime = mime)

  private fun phone(r: Random) = MediaRef.Phone(if (r.nextBoolean()) "external_primary" else "1a2b-3c4d", 1L + r.nextInt(1_000_000), "image/jpeg")

  private fun seed(model: ModelSource, r: Random, n: Int) {
    repeat(n) {
      val m = id(r)
      val kind = r.nextInt(10)
      // 60% solo NAS, 20% telefono, 20% entrambi (di questi alcuni con la copia del telefono non leggibile, E-P22).
      val flags = when {
        kind < 6 -> CellFlags.ON_NAS
        kind < 8 -> CellFlags.ON_PHONE
        else -> CellFlags.ON_PHONE or CellFlags.ON_NAS
      }
      model.media[m] = ModelSource.Media(
        indexRow(m, sortTime = 1_700_000_000_000L - r.nextLong(1_000_000_000L), flags = flags, aspect = 500 + r.nextInt(2500), thumb = if (r.nextBoolean()) null else ByteArray(25) { b -> b.toByte() }),
        ref = if (flags and CellFlags.ON_PHONE != 0 && r.nextInt(8) != 0) phone(r) else null,
        nasRef = if (flags and CellFlags.ON_NAS != 0) nas(r) else null,
      )
    }
  }

  /** Una scrittura casuale; restituisce le identita' toccate e se e' strutturale. */
  private fun mutate(model: ModelSource, r: Random): Pair<List<String>, Boolean> {
    val keys = model.media.keys.toList()
    val pick = { keys[r.nextInt(keys.size)] }
    return when (r.nextInt(8)) {
      0 -> {
        val m = id(r)
        model.media[m] = ModelSource.Media(indexRow(m, sortTime = 1_700_000_000_000L - r.nextLong(1_000_000_000L), flags = CellFlags.ON_NAS), nasRef = nas(r))
        listOf(m) to true
      }
      1 -> {
        val m = pick()
        model.media.remove(m)
        listOf(m) to true
      }
      2 -> {
        // La copia del telefono compare o sparisce: la cella passa dalla miniatura del NAS a quella del telefono e ritorno.
        val m = model.media.entries.filter { CellFlags.has(it.value.row.cellFlags, CellFlags.ON_NAS) }.randomOrNull(r)?.key ?: return emptyList<String>() to false
        val media = model.media.getValue(m)
        media.ref = if (media.ref == null) phone(r) else null
        media.row = media.row.copy(cellFlags = if (media.ref == null) media.row.cellFlags and CellFlags.ON_PHONE.inv() else media.row.cellFlags or CellFlags.ON_PHONE)
        listOf(m) to false
      }
      3 -> {
        // Un file modificato dal PC: contenuto nuovo (chiave nuova), stessa posizione.
        val m = model.media.entries.filter { it.value.nasRef != null }.randomOrNull(r)?.key ?: return emptyList<String>() to false
        val media = model.media.getValue(m)
        media.nasRef = media.nasRef!!.copy(contentKey = r.nextLong())
        listOf(m) to false
      }
      4 -> {
        // Il file sparisce dal NAS (o ricompare): il flag ON_NAS si spegne e la cella perde il riferimento.
        val m = pick()
        val media = model.media.getValue(m)
        val onNas = CellFlags.has(media.row.cellFlags, CellFlags.ON_NAS)
        media.row = media.row.copy(cellFlags = media.row.cellFlags xor CellFlags.ON_NAS)
        if (onNas) media.nasRef = null else media.nasRef = nas(r)
        listOf(m) to false
      }
      5 -> {
        val m = pick()
        model.media.getValue(m).hidden = !model.media.getValue(m).hidden
        listOf(m) to true
      }
      6 -> {
        val m = pick()
        val media = model.media.getValue(m)
        media.row = media.row.copy(sortTime = media.row.sortTime + r.nextLong(-50_000_000L, 50_000_000L), localDay = 20240101)
        listOf(m) to true
      }
      else -> {
        // Solo cambia il MIME del riferimento al NAS (la tabella dei MIME cresce).
        val m = model.media.entries.filter { it.value.nasRef != null }.randomOrNull(r)?.key ?: return emptyList<String>() to false
        val media = model.media.getValue(m)
        media.nasRef = media.nasRef!!.copy(mime = listOf("image/heic", "image/png", "image/webp", null).random(r))
        listOf(m) to false
      }
    }
  }

  private fun TestScope.settle() {
    advanceTimeBy(60_000)
    runCurrent()
  }

  private fun TestScope.repository(source: ModelSource): Pair<CatalogTimelineRepository, SnapshotStore> {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = SnapshotStore(dir, io = dispatcher)
    return CatalogTimelineRepository(source, store, backgroundScope, worker = dispatcher, writeDelayMs = 5_000) to store
  }

  @Test
  fun laPatchEUgualeAllaRicostruzioneConIMediaDelNas() = runTest {
    val r = Random(77)
    val source = ModelSource()
    seed(source, r, 2_500)
    val (repo, store) = repository(source)
    repo.start()
    settle()
    assertSameIndex(repo.snapshot.value!!, source.expected())
    var checks = 0
    repeat(1_500) { step ->
      val (ids, structural) = mutate(source, r)
      if (ids.isEmpty()) return@repeat
      source.commit(ids, structural)
      if (step % 25 == 0) {
        settle()
        assertSameIndex(repo.snapshot.value!!, source.expected())
        checks++
      }
    }
    settle()
    val live = repo.snapshot.value as ColumnarTimelineSnapshot
    assertSameIndex(live, source.expected())
    assertThat(checks).isGreaterThan(50)
    assertThat(repo.rebuilds).isEqualTo(1)
    // E il file `MCGT2` riporta tutto, riferimenti al NAS compresi.
    assertSameIndex(store.read()!!.snapshot, live, checkSeq = false)
    assertThat((0 until live.size).count { live.nasRefAt(it) != null }).isGreaterThan(1_000)
  }

  @Test
  fun ilRiferimentoPerIlViewerEIlTelefonoSeCeAltrimentiIlNas() = runTest {
    val r = Random(5)
    val source = ModelSource()
    val onlyNas = id(r)
    val onBoth = id(r)
    val nasRef = nas(r)
    val phoneRef = phone(r)
    source.media[onlyNas] = ModelSource.Media(indexRow(onlyNas, 200, flags = CellFlags.ON_NAS), nasRef = nasRef)
    source.media[onBoth] = ModelSource.Media(
      indexRow(onBoth, 100, flags = CellFlags.ON_NAS or CellFlags.ON_PHONE), ref = phoneRef, nasRef = nas(r),
    )
    val (repo, _) = repository(source)
    repo.start()
    settle()
    assertThat(repo.mediaRefOf(MediaId(onlyNas))).isEqualTo(nasRef)
    assertThat(repo.refOf(MediaId(onlyNas))).isNull()
    assertThat(repo.mediaRefOf(MediaId(onBoth))).isEqualTo(phoneRef)
    assertThat(repo.refOf(MediaId(onBoth))).isEqualTo(phoneRef)
    val items = TimelineViewerItems(repo, LibraryFilter.ALL)
    assertThat(items.refOrNull(MediaId(onlyNas))).isEqualTo(nasRef)
    assertThat(items.ref(MediaId(onBoth))).isEqualTo(phoneRef)

    // Un media solo-NAS che esce dall'indice (cancellato da un altro dispositivo, E-V6) resta leggibile per un istante:
    // il viewer lo sta ancora mostrando.
    source.media.remove(onlyNas)
    source.commit(listOf(onlyNas), structural = true)
    settle()
    assertThat(repo.snapshot.value!!.indexOf(MediaId(onlyNas))).isEqualTo(-1)
    assertThat(repo.mediaRefOf(MediaId(onlyNas))).isEqualTo(nasRef)
    assertThat(items.refOrNull(MediaId(onlyNas))).isEqualTo(nasRef)
  }

  @Test
  fun unIndiceSenzaNasRestaEsattamenteQuelloDellaFase3() = runTest {
    // Nessun media con ON_NAS e nessun nasRefs: stessa lettura di prima, nessuna richiesta di riferimenti al NAS.
    val r = Random(9)
    val source = ModelSource()
    repeat(500) {
      val m = id(r)
      source.media[m] = ModelSource.Media(indexRow(m, 1_700_000_000_000L - it * 1_000L), ref = phone(r))
    }
    val (repo, _) = repository(source)
    repo.start()
    settle()
    val s = repo.snapshot.value as ColumnarTimelineSnapshot
    assertThat((0 until s.size).count { s.nasRefAt(it) != null }).isEqualTo(0)
    assertThat((0 until s.size).count { s.refAt(it) != null }).isEqualTo(500)
    assertThat(s.nasCount).isEqualTo(0)
  }
}
