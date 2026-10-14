package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimLatencies
import dev.pampa.mycloudgallery.core.nas.testing.SimNasTime
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La fedelta' del simulatore SMB (`docs/fase4-progetto.md` §5.2): file bloccati, latenze e dischi in standby a tempo
 * virtuale, date delle cartelle, ordine degli elenchi, elenchi troncati, copia lato server rifiutata, contenuti ostili,
 * ora del NAS con scarto, deriva e salti.
 */
class SimulatorTest {

  private fun TestScope.harness(clock: ManualMonotonicClock = ManualMonotonicClock()) = Harness(backgroundScope, clock)

  private suspend fun Harness.listNames(dir: String?): List<NasEntry> {
    val entries = ArrayList<NasEntry>()
    manager.mediaReader().list(SHARE, dir?.let { SharePath.parse(it) }, Purpose.BACKGROUND) { entries += it }.value()
    return entries
  }

  @Test
  fun unFileBloccatoDalPcNonSiScriveNeSiRinominaNeSiCancella() = runTest {
    val h = harness()
    h.backend.put(SHARE, "doc/a.jpg", byteArrayOf(1))
    h.backend.lock(SHARE, "DOC/A.JPG", denyRead = false)
    // Si legge (aperto dal PC in lettura e scrittura condivise, E-N13)...
    assertThat(h.manager.mediaReader().open(SHARE, SharePath.of("doc", "a.jpg"), Purpose.BACKGROUND).okOrNull()).isNotNull()
    // ...ma non si cancella.
    val delete = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { it.share.deleteFile("doc\\a.jpg") }
    assertThat(delete.errorOrNull()).isEqualTo(NasError.FileBusy)
    // Un file nostro che il PC apre a meta' lavoro: niente scrittura, niente rinomina.
    val written = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
      ctx.share.createExclusive("doc\\b.tmp").use { file ->
        h.backend.lock(SHARE, "doc/b.tmp", denyRead = false)
        file.write(byteArrayOf(1), 0, 0, 1)
      }
    }
    assertThat(written.errorOrNull()).isEqualTo(NasError.FileBusy)
    val renamed = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
      ctx.share.createExclusive("doc\\c.tmp").use { file ->
        h.backend.lock(SHARE, "doc/c.tmp", denyRead = false)
        file.renameNoReplace("doc\\d.jpg")
      }
    }
    assertThat(renamed.errorOrNull()).isEqualTo(NasError.FileBusy)
    // In esclusiva (Office): nemmeno la lettura.
    h.backend.lock(SHARE, "doc/a.jpg", denyRead = true)
    assertThat(h.manager.mediaReader().open(SHARE, SharePath.of("doc", "a.jpg"), Purpose.BACKGROUND).errorOrNull()).isEqualTo(NasError.FileBusy)
    h.backend.unlock(SHARE, "doc/a.jpg")
    assertThat(h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { it.share.deleteFile("doc\\a.jpg") })
      .isInstanceOf(dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok::class.java)
  }

  @Test
  fun leDateDelleCartelleSeguonoCreazioniRinomineECancellazioni() = runTest {
    var now = 1_800_000_000_000L
    run {
      val clock = ManualMonotonicClock()
      val h = harness(clock)
      h.backend.nasTime = { now }
      h.backend.put(SHARE, "a/vecchio.jpg", byteArrayOf(1), lastWriteMs = now)
      fun folder(path: String) = h.backend.lastWriteMs(SHARE, path)
      now += 1_000
      h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx -> ctx.share.createExclusive("a\\nuovo.jpg").close() }.value()
      assertThat(folder("a")).isEqualTo(now)
      now += 1_000
      h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx -> ctx.share.mkdir("b") }.value()
      h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
        ctx.share.createExclusive("a\\temp").use { it.renameNoReplace("b\\spostato") }
      }.value()
      assertThat(folder("a")).isEqualTo(now)
      assertThat(folder("b")).isEqualTo(now)
      now += 1_000
      h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx -> ctx.share.deleteFile("a\\nuovo.jpg") }.value()
      assertThat(folder("a")).isEqualTo(now)
      // Una modifica sul posto dal PC non cambia la data della cartella (E-N2), quella del file si'.
      now += 1_000
      h.backend.modify(SHARE, "a/vecchio.jpg", NasContent.of(byteArrayOf(2)))
      assertThat(folder("a")).isEqualTo(now - 1_000)
      assertThat(h.backend.lastWriteMs(SHARE, "a/vecchio.jpg")).isEqualTo(now)
      // Una cartella vuota si toglie solo se e' vuota.
      val notEmpty = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { it.share.removeEmptyDirectory("a") }
      assertThat(notEmpty.errorOrNull()).isEqualTo(NasError.Unexpected(NtStatus.DIRECTORY_NOT_EMPTY))
    }
  }

  @Test
  fun leLatenzeEIlRisveglioDeiDischiPassanoATempoVirtuale() = runTest {
    val clock = ManualMonotonicClock()
    val h = harness(clock)
    h.backend.time = clock
    h.backend.latencies = SimLatencies.INVENTORY
    h.backend.standbyAfterMs = 10 * 60_000L
    repeat(12_000) { h.backend.putContent(SHARE, "foto/$it.jpg", NasContent.of(byteArrayOf(1))) }

    val started = clock.nowMillis()
    h.listNames("foto")
    val listing = clock.nowMillis() - started
    // Prima operazione sui dischi addormentati: +8 s; poi ~0,15 ms per voce (12 mila voci ≈ 1,8 s).
    assertThat(h.backend.diskWakeUps.get()).isEqualTo(1)
    assertThat(listing).isIn(8_000L..11_000L)
    assertThat(h.manager.lastDiskTouchAt.value).isNotNull()

    // Dischi svegli: nessun nuovo risveglio. Dopo 10 minuti senza operazioni dormono di nuovo.
    h.listNames("foto")
    assertThat(h.backend.diskWakeUps.get()).isEqualTo(1)
    clock.advance(10 * 60_000L)
    h.listNames("foto")
    assertThat(h.backend.diskWakeUps.get()).isEqualTo(2)

    // Lettura a flusso: ~250 ms per MiB (un flusso).
    h.backend.put(SHARE, "v.mp4", ByteArray(8 * 1024 * 1024))
    val file = h.manager.mediaReader().open(SHARE, SharePath.of("v.mp4"), Purpose.BACKGROUND).value()
    val before = clock.nowMillis()
    h.manager.mediaReader().readAll(file, { _, _ -> }).value()
    assertThat(clock.nowMillis() - before).isIn(2_000L..2_200L)
  }

  @Test
  fun lOrdineDegliElenchiNonEGarantitoEUnElencoPuoEssereTroncato() = runTest {
    val h = harness()
    repeat(200) { h.backend.put(SHARE, "foto/$it.jpg", byteArrayOf(1)) }
    val natural = h.listNames("foto").map { it.name }
    h.backend.listOrder = Random(4)
    val shuffled = h.listNames("foto").map { it.name }
    assertThat(shuffled).containsExactlyElementsIn(natural)
    assertThat(shuffled).isNotEqualTo(natural)

    h.backend.listOrder = null
    h.backend.truncateListsAfter = 50
    assertThat(h.listNames("foto")).hasSize(50)
  }

  @Test
  fun laCopiaLatoServerRifiutataArrivaComeErroreSenzaLasciareTemporanei() = runTest {
    val h = harness()
    h.backend.put(SHARE, "a.jpg", ByteArray(100))
    val copy = SmbServerSideCopy(h.manager)

    h.backend.copyChunkStatus = NtStatus.NOT_SUPPORTED
    assertThat(copy.copy(SHARE, SharePath.of("a.jpg"), SHARE, SharePath.of("b.jpg")).errorOrNull())
      .isEqualTo(NasError.Unexpected(NtStatus.NOT_SUPPORTED))
    h.backend.copyChunkStatus = NtStatus.ACCESS_DENIED
    assertThat(copy.copy(SHARE, SharePath.of("a.jpg"), SHARE, SharePath.of("b.jpg")).errorOrNull()).isEqualTo(NasError.PermissionDenied)

    assertThat(h.backend.exists(SHARE, "b.jpg")).isFalse()
    assertThat(h.backend.children(SHARE, ".mycloudgallery/tmp")).isEmpty()
  }

  @Test
  fun contenutiOstiliSiElencanoComeSonoSenzaSeguireICollegamenti() = runTest {
    val h = harness()
    for (name in listOf("CON", "nome con punto.", "due:punti", "emoji 🎞️.mp4", "NUL.txt")) h.backend.put(SHARE, "strani/$name", byteArrayOf(1))
    h.backend.putLink(SHARE, "strani/ciclo", target = "strani")
    h.backend.putDir(SHARE, "strani/.recycle", attributes = NasAttributes.HIDDEN or NasAttributes.SYSTEM)

    val entries = h.listNames("strani")
    assertThat(entries.map { it.name }).containsAtLeast("CON", "nome con punto.", "due:punti", "emoji 🎞️.mp4", "NUL.txt", "ciclo", ".recycle")
    val link = entries.single { it.name == "ciclo" }
    assertThat(link.isReparsePoint).isTrue()
    assertThat(link.isDirectory).isTrue()
    assertThat(entries.single { it.name == ".recycle" }.isSystem).isTrue()
    // Il collegamento porta alla cartella stessa: chi elenca deve guardare gli attributi (E-N10), il server non lo ferma.
    val throughLink = h.listNames("strani/ciclo").map { it.name }
    assertThat(throughLink).contains("ciclo")
  }

  @Test
  fun lOraDelNasHaScartoDerivaESalti() {
    var elapsed = 0L
    val time = SimNasTime(trueStartMs = 1_800_000_000_000L, elapsed = { elapsed })
    assertThat(time.now()).isEqualTo(1_800_000_058_000L)
    elapsed = 24L * 60 * 60 * 1000
    assertThat(time.now() - (1_800_000_000_000L + elapsed)).isEqualTo(63_000L)
    time.jump(-63_000L)
    assertThat(time.now()).isEqualTo(1_800_000_000_000L + elapsed)
  }

  @Test
  fun unaCartellaEnormeSiElencaConUnSoloHandle() = runTest {
    val h = harness()
    repeat(30_000) { h.backend.putContent(SHARE, "enorme/$it", NasContent.of(byteArrayOf(1))) }
    var opens = 0
    h.backend.beforeOp = { op, _ -> if (op == dev.pampa.mycloudgallery.core.nas.testing.SimOp.OPEN_DIRECTORY) opens++ }
    var count = 0
    h.manager.mediaReader().list(SHARE, SharePath.of("enorme"), Purpose.BACKGROUND, NasMediaLimits.DEFAULT_PAGE_SIZE) { count += it.size }.value()
    assertThat(count).isEqualTo(30_000)
    assertThat(opens).isEqualTo(1)
  }

  @Test
  fun unaCartellaMancanteNelPercorsoEUnErroreDiSamba() = runTest {
    val h = harness()
    val result = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
      try {
        ctx.share.createExclusive("manca\\a.jpg")
        null
      } catch (e: SmbStatusException) {
        e.status
      }
    }
    assertThat(result.value()).isEqualTo(NtStatus.OBJECT_PATH_NOT_FOUND)
  }
}
