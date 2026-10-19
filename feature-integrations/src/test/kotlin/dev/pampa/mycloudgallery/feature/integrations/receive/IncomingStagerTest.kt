package dev.pampa.mycloudgallery.feature.integrations.receive

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.HangingStream
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.OpenCounter
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.incomingStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P11 (D24, C18, E-X2, §4.3 "Lettura difensiva"): la copia immediata dei file in arrivo con **URI ostili, tipi falsi e tetti**. Ogni rifiuto e'
 * motivato e **non lascia niente su disco**; `file://` e i provider dell'app non si aprono nemmeno; i tetti valgono prima di scrivere e mentre si
 * scrive; un flusso che si pianta si abbandona; il nome locale e' l'hash dei byte.
 */
class IncomingStagerTest {

  @get:Rule val folder = TemporaryFolder()

  private val root: File get() = File(folder.root, "incoming")

  private fun stager(
    free: () -> Long = { 100L shl 30 },
    limits: StageLimits = StageLimits(),
    bounds: ImageBoundsReader = ImageBoundsReader { null },
    stallMs: Long = 5_000,
  ) = IncomingStager(root, freeBytes = free, limits = limits, bounds = bounds, stallMs = stallMs)

  private fun stage(stager: IncomingStager, vararg streams: IncomingStream) = runBlocking { stager.stage(streams.toList()) }

  /** Nessun file e nessuna cartella di condivisione in `incoming/` (anche aspettando un lavoro abbandonato che finisce di pulire). */
  private fun assertNothingOnDisk() {
    val deadline = System.nanoTime() + 3_000_000_000L
    while (root.listFiles().orEmpty().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
    assertThat(root.listFiles().orEmpty().toList()).isEmpty()
  }

  // --- URI ostili -----------------------------------------------------------------------------------------------------------

  @Test
  fun fileSchemeEProviderNostriSiRifiutanoSenzaAprireIlFlusso() {
    val counter = OpenCounter()

    val stage = stage(
      stager(),
      counter.stream(IncomingOrigin.FILE_SCHEME),
      counter.stream(IncomingOrigin.OWN_PROVIDER),
      counter.stream(IncomingOrigin.FILE_SCHEME),
    )

    assertThat(counter.opened).isEqualTo(0) // mai aperti: il rifiuto e' sull'indirizzo
    assertThat(stage.accepted).isEmpty()
    assertThat(stage.rejected).containsExactly(IncomingReject.FILE_SCHEME, 2, IncomingReject.OWN_PROVIDER, 1)
    assertNothingOnDisk() // niente copiato, nemmeno la cartella della condivisione
  }

  @Test
  fun unJpgCheDentroEHtmlTestoOPdfNonEUnMediaENonLasciaNiente() {
    val fakes = listOf(
      ReceiveSamples.fakeJpegThatIsHtml(),
      "{\"errore\": 404}".toByteArray(),
      "%PDF-1.7 ....".toByteArray() + ByteArray(100) { 0xFF.toByte() },
      byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00) + ByteArray(100) { it.toByte() },
      ByteArray(2_000), // un file azzerato
      ByteArray(0),
    )

    val stage = stage(stager(), *fakes.map { incomingStream(it, displayName = "foto.jpg", declaredMime = "image/jpeg") }.toTypedArray())

    assertThat(stage.accepted).isEmpty()
    assertThat(stage.rejected).containsExactly(IncomingReject.NOT_MEDIA, fakes.size)
    assertNothingOnDisk()
  }

  @Test
  fun ilTipoLoDicononoIByteNonIlNomeNeIlTipoDichiarato() {
    val png = ReceiveSamples.png()
    val m4a = ReceiveSamples.mp4(brand = "M4A ")

    val stage = stage(
      stager(),
      incomingStream(png, displayName = "e-un-jpg.jpg", declaredMime = "image/jpeg"),
      incomingStream(ReceiveSamples.jpeg(), displayName = "e-un-video.mp4", declaredMime = "video/mp4"),
      incomingStream(m4a, displayName = "audio.mp4", declaredMime = "video/mp4"),
    )

    assertThat(stage.accepted.map { it.mime }).containsExactly("image/png", "image/jpeg")
    assertThat(stage.rejected).containsExactly(IncomingReject.NOT_MEDIA, 1) // un contenitore audio non e' un video
  }

  // --- Tetti ----------------------------------------------------------------------------------------------------------------

  @Test
  fun unaDimensioneDichiarataOltreIlTettoNonSiApre() {
    val counter = OpenCounter()
    val tooBig = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", Long.MAX_VALUE) { counter.stream(IncomingOrigin.CONTENT_OF_ANOTHER_APP).open() }

    val stage = stage(stager(limits = StageLimits(maxFileBytes = 10_000)), tooBig)

    assertThat(counter.opened).isEqualTo(0)
    assertThat(stage.rejected).containsExactly(IncomingReject.TOO_LARGE, 1)
  }

  @Test
  fun unFlussoCheNonFinisceSiInterrompeAlTettoENonLasciaIlFileAMeta() {
    val endless = ReceiveSamples.Endless(ReceiveSamples.jpeg(2_000))
    val limits = StageLimits(maxFileBytes = 1_000_000)

    val stage = stage(stager(limits = limits), IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", null) { endless })

    assertThat(stage.rejected).containsExactly(IncomingReject.TOO_LARGE, 1)
    assertThat(stage.accepted).isEmpty()
    // Non l'ha letto fino in fondo (un flusso infinito): al massimo il tetto piu' un blocco.
    assertThat(endless.produced).isLessThan(1_000_000L + 512 * 1024)
    assertNothingOnDisk()
  }

  @Test
  fun iTettiDellaCondivisione_TroppiElementi_TroppoGrande_FiltrandoGliAltri() {
    val limits = StageLimits(maxFileBytes = 5_000, maxStageBytes = 9_000, maxItems = 2)
    val counter = OpenCounter()

    val stage = stage(
      stager(limits = limits),
      incomingStream(ReceiveSamples.jpeg(4_000, 1)),
      incomingStream(ReceiveSamples.jpeg(4_000, 2)),
      // Il terzo e' oltre i 2 elementi: non si apre.
      IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", null, 4_000) { counter.stream(IncomingOrigin.CONTENT_OF_ANOTHER_APP).open() },
    )

    assertThat(stage.accepted).hasSize(2)
    assertThat(stage.rejected).containsExactly(IncomingReject.TOO_MANY, 1)
    assertThat(counter.opened).isEqualTo(0)

    // 4 GiB in tutto (qui 9.000 byte): il terzo file da 4.000 supera il totale.
    val big = StageLimits(maxFileBytes = 5_000, maxStageBytes = 9_000, maxItems = 10)
    val stage2 = stage(
      stager(limits = big),
      incomingStream(ReceiveSamples.jpeg(4_000, 1)),
      incomingStream(ReceiveSamples.jpeg(4_000, 2)),
      incomingStream(ReceiveSamples.jpeg(4_000, 3)),
    )
    assertThat(stage2.accepted).hasSize(2)
    assertThat(stage2.rejected).containsExactly(IncomingReject.STAGE_TOO_BIG, 1)
  }

  @Test
  fun unaInfinitaDiIndirizziSiContaENonSiGuarda() {
    val counter = OpenCounter()
    val streams = List(50) { counter.stream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, ReceiveSamples.jpeg(1_000, it)) }

    val stage = stage(stager(limits = StageLimits(maxExamined = 10, maxItems = 500)), *streams.toTypedArray())

    assertThat(counter.opened).isEqualTo(10)
    assertThat(stage.accepted).hasSize(10)
    assertThat(stage.rejected).containsExactly(IncomingReject.TOO_MANY, 40)
  }

  @Test
  fun dopoLaCopiaDeveRestareAlmenoUnGigabyteLibero() {
    // Spazio dichiarato: il file da 4.000 byte lascerebbe meno del minimo.
    val low = stager(free = { 1_000_000L }, limits = StageLimits(minFreeAfterBytes = 999_000L))

    val declared = stage(low, incomingStream(ReceiveSamples.jpeg(4_000)))
    assertThat(declared.accepted).isEmpty()
    assertThat(declared.rejected).containsExactly(IncomingReject.NO_SPACE, 1)

    // Spazio che si consuma mentre si copia (nessuna dimensione dichiarata): si ferma quando scende sotto il minimo.
    var free = 100L * 1024 * 1024
    val shrinking = stager(free = { free }, limits = StageLimits(minFreeAfterBytes = 50L * 1024 * 1024))
    val stream = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", null) {
      object : java.io.InputStream() {
        val head = ReceiveSamples.jpeg(4_000)
        var at = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
          free -= 20L * 1024 * 1024 // ogni lettura mangia spazio, come un disco che si riempie
          val n = minOf(len, 100_000)
          for (i in 0 until n) b[off + i] = head[(at + i) % head.size]
          at += n
          return n
        }
      }
    }
    val stage = stage(shrinking, stream)
    assertThat(stage.rejected).containsExactly(IncomingReject.NO_SPACE, 1)
    assertNothingOnDisk()
  }

  // --- Flussi che non vanno ----------------------------------------------------------------------------------------------------

  @Test
  fun unFlussoCheNonSiApreOSiInterrompeEUnReiettoMotivatoEGliAltriProseguono() {
    val broken = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", null, null) { throw IOException("provider morto") }
    val denied = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", null, null) { throw SecurityException("nessun permesso") }
    val npe = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", null, null) { throw IllegalStateException("provider rotto") }
    val revoked = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", null) { ReceiveSamples.RevokedAfter(ReceiveSamples.jpeg(50_000), 20_000) }

    val stage = stage(stager(), broken, denied, npe, revoked, incomingStream(ReceiveSamples.jpeg(3_000, 9)))

    assertThat(stage.rejected).containsExactly(IncomingReject.UNREADABLE, 4)
    // Il file buono, dopo quelli rotti, e' stato copiato: il permesso ritirato a meta' di uno non ferma gli altri.
    assertThat(stage.accepted).hasSize(1)
    assertThat(root.listFiles()!!.single().listNames()).containsExactly(stage.accepted.single().name)
  }

  @Test
  fun unFlussoCheSiPiantaSiAbbandonaENonBloccaLaCondivisione() {
    val hanging = HangingStream()
    val stalled = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", null) { hanging }

    val stage = runBlocking {
      withTimeout(10_000) { stager(stallMs = 300).stage(listOf(stalled, incomingStream(ReceiveSamples.jpeg(3_000, 5)))) }
    }

    assertThat(stage.rejected).containsExactly(IncomingReject.UNREADABLE, 1)
    assertThat(stage.accepted).hasSize(1)
    assertThat(hanging.closed).isTrue() // l'abbiamo chiuso noi, per non lasciarlo aperto
  }

  @Test
  fun unaCancellazioneAMetaNonLasciaFileNeCartelle() {
    val endless = ReceiveSamples.Endless(ReceiveSamples.jpeg(2_000))
    val slow = IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "x", "image/jpeg", null) {
      object : java.io.InputStream() {
        override fun read(): Int = 1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          Thread.sleep(5)
          return endless.read(b, off, minOf(len, 1_000))
        }
      }
    }
    runBlocking {
      val job = CoroutineScope(Dispatchers.IO).async { stager(limits = StageLimits(maxFileBytes = 100L * 1024 * 1024)).stage(listOf(incomingStream(ReceiveSamples.jpeg(3_000, 1)), slow)) }
      delay(150)
      job.cancelAndJoin()
    }

    // La cartella della condivisione non esiste piu', ne' i file copiati prima.
    assertNothingOnDisk()
  }

  // --- Nomi, hash, immagini a bomba ------------------------------------------------------------------------------------------------

  @Test
  fun ilNomeLocaleELHashDeiByteEIlNomeDellAltraAppNonEntraInUnPercorso() {
    val bytes = ReceiveSamples.jpeg(3_500, 7)
    val hostileName = "../../../../databases/mycloud.db\u0000.jpg"

    val stage = stage(stager(), incomingStream(bytes, displayName = hostileName, declaredMime = "image/jpeg"))

    val item = stage.accepted.single()
    assertThat(item.name).isEqualTo(ReceiveSamples.sha256(bytes))
    assertThat(item.sha256.hex).isEqualTo(item.name)
    assertThat(item.size).isEqualTo(bytes.size.toLong())
    assertThat(item.displayName).doesNotContain("/")
    assertThat(item.displayName).doesNotContain("\u0000")
    val onDisk = File(File(root, stage.id.value), item.name)
    assertThat(onDisk.readBytes()).isEqualTo(bytes)
    // Nessun altro file: nessun resto di copie a meta' (`.w*`).
    assertThat(File(root, stage.id.value).listNames()).containsExactly(item.name)
  }

  @Test
  fun lostessoContenutoDueVolteNellaStessaCondivisioneSiTieneUnaVoltaSola() {
    val bytes = ReceiveSamples.jpeg(3_000, 3)

    val stage = stage(stager(), incomingStream(bytes, displayName = "a.jpg"), incomingStream(bytes.copyOf(), displayName = "b.jpg"))

    assertThat(stage.accepted).hasSize(1)
    assertThat(File(root, stage.id.value).listNames()).hasSize(1)
  }

  @Test
  fun unImmagineCheDichiaraPixelDaBombaSiRifiutaPrimaCheQualcosaLaDecodifichi() {
    val bomb = ImageBoundsReader { ImageSize(60_000, 60_000) }
    val fine = ImageBoundsReader { ImageSize(4_000, 3_000) }

    val rejected = stage(stager(bounds = bomb), incomingStream(ReceiveSamples.jpeg(), declaredMime = "image/jpeg"))
    assertThat(rejected.rejected).containsExactly(IncomingReject.TOO_LARGE, 1)
    assertNothingOnDisk()

    val accepted = stage(stager(bounds = fine), incomingStream(ReceiveSamples.jpeg(), declaredMime = "image/jpeg"))
    assertThat(accepted.accepted).hasSize(1)

    // Un video non ha dimensioni di immagine da controllare.
    val video = stage(stager(bounds = bomb), incomingStream(ReceiveSamples.mp4(), declaredMime = "video/mp4"))
    assertThat(video.accepted.single().mime).isEqualTo("video/mp4")
  }

  // --- Dopo la copia -------------------------------------------------------------------------------------------------------------

  @Test
  fun scartareEPulireTolgonoLeCopieSenzaToccareLeCondivisioniInCoda() {
    val stager = stager()
    val a = stage(stager, incomingStream(ReceiveSamples.jpeg(3_000, 1)))
    val b = stage(stager, incomingStream(ReceiveSamples.jpeg(3_000, 2)))
    val c = stage(stager, incomingStream(ReceiveSamples.jpeg(3_000, 3)))
    File(File(root, b.id.value), ".w7").writeText("a meta'") // una copia rimasta a meta' in una condivisione citata dal database

    runBlocking { stager.discard(a.id) }
    assertThat(File(root, a.id.value).exists()).isFalse()
    runBlocking { stager.discard(a.id) } // idempotente

    stager.sweep(keep = setOf(b.id.value))

    assertThat(root.listNames()).containsExactly(b.id.value)
    assertThat(File(root, b.id.value).listNames()).containsExactly(b.accepted.single().name) // la citata resta, il resto a meta' no
    assertThat(File(root, c.id.value).exists()).isFalse()
  }

  @Test
  fun unFileSostituitoOTolto_NonEPiuIntatto_ENomiStraniNonSiRisolvono() {
    val stager = stager()
    val stage = stage(stager, incomingStream(ReceiveSamples.jpeg(3_000, 1)), incomingStream(ReceiveSamples.png(3_000, 2)))
    assertThat(stager.isIntact(stage)).isTrue()

    val first = stage.accepted[0]
    File(File(root, stage.id.value), first.name).appendBytes(byteArrayOf(1)) // modificato dopo la copia
    assertThat(stager.isIntact(stage)).isFalse()

    // Un nome che non e' un hash non si risolve in un file (nessun separatore, nessun "..").
    val forged = dev.pampa.mycloudgallery.core.backup.contract.IncomingItem("../x", first.sha256, first.size, first.mime, null)
    assertThat(stager.fileOf(stage.id, forged)).isNull()
  }
}
