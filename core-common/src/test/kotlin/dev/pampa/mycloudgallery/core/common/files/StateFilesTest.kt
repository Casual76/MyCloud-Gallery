package dev.pampa.mycloudgallery.core.common.files

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.common.log.LogSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I file di stato locali ([StateFiles]): scrittura atomica, cancellazione verificata, errori che non spariscono.
 *
 * Su Windows (e quindi sulla JVM di questa macchina) `java.io` apre i file senza `FILE_SHARE_DELETE`: finche' un altro handle e' aperto,
 * `File.delete()` e `File.renameTo()` falliscono **in silenzio**. Le prove con "un file tenuto aperto" riproducono proprio quello, con
 * un `FileInputStream` (e' cio' che apre `File.readText()`), e chiudono l'handle **dentro l'attesa** del codice che si prova
 * ([Patience.pause]): niente orologio, quindi nessun test che diventa rosso sotto carico. Su Android/Linux un file aperto non impedisce
 * di cancellarlo ne' di sostituirlo: le stesse prove passano senza attese.
 */
class StateFilesTest {

  @get:Rule
  val temp = TemporaryFolder()

  private class RecordingSink : LogSink {
    val lines = ConcurrentLinkedQueue<Pair<LogLevel, String>>()
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
      lines += level to message
    }
  }

  @After
  fun tearDown() {
    GalleryLog.uninstall()
    Thread.interrupted()
  }

  private fun file(name: String = "stato") = File(temp.root, name)

  /** Un'attesa che, la prima volta, chiude [handle] (l'altro handle finalmente lascia il file) e conta quante volte si e' aspettato. */
  private class ReleasingPatience(private val handle: AutoCloseable?) {
    var waits = 0
      private set
    val patience = Patience(listOf(1L, 1L, 1L, 1L, 1L)) {
      waits++
      handle?.close()
    }
  }

  private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

  // --- scrittura -------------------------------------------------------------------------------------------------------------

  @Test
  fun scrive_creaLeCartelleCheMancanoENonLasciaIlTemporaneo() {
    val target = File(temp.root, "a/b/stato")

    StateFiles.writeText(target, "ciao", Charsets.US_ASCII)

    assertThat(target.readText()).isEqualTo("ciao")
    assertThat(target.parentFile!!.list().orEmpty().toList()).containsExactly("stato")
  }

  @Test
  fun scrive_sostituisceIlContenutoVecchioPerIntero() {
    val target = file()
    StateFiles.writeText(target, "una riga molto lunga, lunga, lunga")

    StateFiles.writeText(target, "corta")

    assertThat(target.readText()).isEqualTo("corta")
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
  }

  @Test
  fun scrive_tieneIBytesEsattiEDicePerCharset() {
    val target = file()
    val bytes = ByteArray(300) { it.toByte() }

    StateFiles.writeBytes(target, bytes)
    assertThat(StateFiles.readBytes(target)).isEqualTo(bytes)

    StateFiles.writeText(target, "perche'", Charsets.UTF_16LE)
    assertThat(StateFiles.readText(target, Charsets.UTF_16LE)).isEqualTo("perche'")
  }

  @Test
  fun scrive_conIlFileTenutoApertoDaUnAltroHandle_aspettaESostituisce() {
    val target = file()
    StateFiles.writeText(target, "vecchio")
    val other = FileInputStream(target)
    val releasing = ReleasingPatience(other)

    other.use {
      StateFiles.writeText(target, "nuovo", patience = releasing.patience)
    }

    assertThat(target.readText()).isEqualTo("nuovo")
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
    // Su Windows l'handle aperto fa fallire il primo tentativo: si e' aspettato davvero (il codice di prima si arrendeva qui, in silenzio).
    if (windows) assertThat(releasing.waits).isAtLeast(1)
  }

  @Test
  fun scrive_seNonRiesce_lanciaEIlFileDiPrimaERestaIntero() {
    // Un errore che nessuna attesa risolve, uguale su Windows e su Linux: il nome del file e' occupato da una cartella non vuota.
    val target = file()
    target.mkdirs()
    File(target, "dentro").writeText("1")
    val waited = ArrayList<Long>()

    val error = runCatching {
      StateFiles.writeText(target, "nuovo", patience = Patience(listOf(1L, 2L)) { waited += it })
    }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(File(target, "dentro").readText()).isEqualTo("1")
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
    // I10: si aspetta solo dove l'attesa serve (Windows); su Android e Linux un errore vero non costa 310 ms dentro il lucchetto.
    assertThat(waited).isEqualTo(if (windows) listOf(1L, 2L) else emptyList())
  }

  @Test
  fun scrive_unErroreFinisceNelLogConIlNomeDelFileESenzaPercorsi() {
    val sink = RecordingSink()
    GalleryLog.install(sink)
    val target = file("memoria-del-giro")
    target.mkdirs()
    File(target, "dentro").writeText("1")

    val error = runCatching { StateFiles.writeText(target, "x", patience = Patience.NONE) }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(error!!.message).contains("memoria-del-giro")
    val warnings = sink.lines.filter { it.first == LogLevel.WARN }.map { it.second }
    assertThat(warnings).isNotEmpty()
    assertThat(warnings.joinToString("\n")).contains("memoria-del-giro")
    for (line in warnings + error.message.orEmpty()) assertThat(line).doesNotContain(temp.root.path)
  }

  @Test
  fun scrive_laLabelSostituisceIlNomeNelLog() {
    val sink = RecordingSink()
    GalleryLog.install(sink)
    val target = file("0123456789abcdef")
    target.mkdirs()
    File(target, "dentro").writeText("1")

    runCatching { StateFiles.writeText(target, "x", label = "inventario del cestino", patience = Patience.NONE) }

    val text = sink.lines.joinToString("\n") { it.second }
    assertThat(text).contains("inventario del cestino")
    assertThat(text).doesNotContain("0123456789abcdef")
  }

  @Test
  fun scrive_unaInterruzioneDuranteLAttesaFermaIlRiprovoELasciaIlFlag() {
    val target = file()
    target.mkdirs()
    File(target, "dentro").writeText("1")

    val error = runCatching {
      StateFiles.writeText(target, "x", patience = Patience(listOf(1L, 1L)) { throw InterruptedException() })
    }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(Thread.currentThread().isInterrupted).isTrue()
  }

  @Test
  fun scriveAPezzi_ilCorpoPuoChiudereLoStreamEIlFileResta()  {
    val target = file()

    StateFiles.write(target) { raw ->
      raw.bufferedWriter(Charsets.US_ASCII).use { out ->
        out.write("riga uno")
        out.newLine()
        out.write("riga due")
      }
    }

    assertThat(target.readLines()).containsExactly("riga uno", "riga due").inOrder()
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
  }

  @Test
  fun scriveAPezzi_seIlCorpoFallisceIlFileDiPrimaEIntattoENessunTemporaneoResta() {
    val target = file()
    StateFiles.writeText(target, "intero")

    val error = runCatching {
      StateFiles.write(target) { out ->
        out.write("a meta".toByteArray())
        throw IllegalStateException("morto a meta'")
      }
    }.exceptionOrNull()

    assertThat(error).isInstanceOf(IllegalStateException::class.java)
    assertThat(target.readText()).isEqualTo("intero")
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
  }

  @Test
  fun sostituisce_mettePostoUnTemporaneoScrittoDaAltri() {
    val target = file()
    StateFiles.writeText(target, "vecchio")
    val prepared = File(temp.root, "stato.tmp")
    prepared.writeText("nuovo")

    StateFiles.replace(prepared, target)

    assertThat(target.readText()).isEqualTo("nuovo")
    assertThat(prepared.exists()).isFalse()
  }

  @Test
  fun sostituisce_conIlFileTenutoApertoDaUnAltroHandle_aspettaESostituisce() {
    val target = file()
    StateFiles.writeText(target, "vecchio")
    val prepared = File(temp.root, "preparato")
    prepared.writeText("nuovo")
    val other = FileInputStream(target)
    val releasing = ReleasingPatience(other)

    other.use { StateFiles.replace(prepared, target, patience = releasing.patience) }

    assertThat(target.readText()).isEqualTo("nuovo")
    if (windows) assertThat(releasing.waits).isAtLeast(1)
  }

  @Test
  fun sostituisce_seNonRiesceLanciaEIlFileDiPrimaResta() {
    val target = file()
    target.mkdirs()
    File(target, "dentro").writeText("1")
    val prepared = File(temp.root, "preparato")
    prepared.writeText("nuovo")

    val error = runCatching { StateFiles.replace(prepared, target, patience = Patience.NONE) }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(prepared.readText()).isEqualTo("nuovo")
    assertThat(File(target, "dentro").readText()).isEqualTo("1")
  }

  @Test
  fun legge_leRigheCome_readLines_senzaUnaRigaVuotaInFondo() {
    val target = file()
    target.writeText("uno\ndue\r\ntre\n")

    assertThat(StateFiles.readLines(target)).containsExactly("uno", "due", "tre").inOrder()
    assertThat(StateFiles.readLines(file("manca"))).isNull()
  }

  // --- lettori concorrenti ----------------------------------------------------------------------------------------------------

  /**
   * Legge a ripetizione finche' [stop] e' falso; ogni cosa diversa da "tutta una lettera di [valid]" e' un problema. Fra una lettura e
   * l'altra respira un millisecondo, come un lettore vero: su Windows qualunque handle aperto fa fallire la sostituzione, e un lettore
   * che riapre il file senza mai fermarsi lo terrebbe aperto di continuo (nessuna attesa basterebbe, e non e' il caso che si prova).
   */
  private fun readerOf(target: File, stop: AtomicBoolean, valid: Set<Char>, size: Int, problems: ConcurrentLinkedQueue<String>): Thread = thread {
    while (!stop.get()) {
      try {
        val text = StateFiles.readText(target, Charsets.US_ASCII)
        when {
          text == null -> problems += "file assente"
          text.length != size -> problems += "file a meta': ${text.length} di $size"
          text.any { it != text[0] } -> problems += "contenuti mescolati"
          text[0] !in valid -> problems += "contenuto sconosciuto"
        }
      } catch (e: IOException) {
        problems += "non leggibile: ${e.message}"
      }
      Thread.sleep(1)
    }
  }

  /**
   * Scrive finche' riesce. Sotto carico lo scheduler puo' fermare il lettore con il file aperto per piu' dei sei tentativi della pazienza di
   * serie, e allora la scrittura lancia (lo dice, ed e' giusto): qui non si prova la pazienza (lo fanno le prove sopra, senza orologio) ma che un
   * lettore non veda niente di strano, quindi la scrittura si ripete. Un tetto largo di tentativi dice se non riesce davvero mai.
   */
  private fun writeUntilDone(target: File, content: String) {
    var attempts = 0
    while (true) {
      try {
        StateFiles.writeText(target, content, Charsets.US_ASCII, patience = Patience.NONE)
        return
      } catch (e: IOException) {
        check(++attempts < MAX_ATTEMPTS) { "la scrittura non riesce mai: $e" }
        Thread.sleep(1)
      }
    }
  }

  @Test
  fun scrive_unLettoreConcorrenteNonVedeMaiUnFileAMetaOChePrimaMancava() {
    val target = file()
    val size = 64 * 1024
    StateFiles.writeText(target, "A".repeat(size), Charsets.US_ASCII)
    val stop = AtomicBoolean(false)
    val problems = ConcurrentLinkedQueue<String>()
    val reader = readerOf(target, stop, setOf('A', 'B', 'C'), size, problems)

    try {
      repeat(200) { i -> writeUntilDone(target, (if (i % 2 == 0) "B" else "C").repeat(size)) }
    } finally {
      stop.set(true)
      reader.join()
    }

    assertThat(problems.toList().distinct()).isEmpty()
  }

  @Test
  fun scrive_piuScrittoriDelloStessoFileNonSiMescolano() {
    val target = file()
    val size = 32 * 1024
    StateFiles.writeText(target, "A".repeat(size), Charsets.US_ASCII)
    val stop = AtomicBoolean(false)
    val problems = ConcurrentLinkedQueue<String>()
    val reader = readerOf(target, stop, setOf('A', 'B', 'C', 'D', 'E'), size, problems)
    val failures = ConcurrentLinkedQueue<Throwable>()

    val writers = "BCDE".map { letter ->
      thread {
        try {
          repeat(60) { writeUntilDone(target, letter.toString().repeat(size)) }
        } catch (e: Throwable) {
          failures += e
        }
      }
    }
    writers.forEach { it.join() }
    stop.set(true)
    reader.join()

    assertThat(failures.toList()).isEmpty()
    assertThat(problems.toList().distinct()).isEmpty()
    assertThat(temp.root.list().orEmpty().toList()).containsExactly("stato")
  }

  // --- cancellazione ---------------------------------------------------------------------------------------------------------

  @Test
  fun cancella_unFileCheNonCeEVero() {
    assertThat(StateFiles.delete(file("manca"))).isTrue()
  }

  @Test
  fun cancella_toglieIlFile() {
    val target = file()
    target.writeText("1")

    assertThat(StateFiles.delete(target)).isTrue()

    assertThat(target.exists()).isFalse()
  }

  @Test
  fun cancella_conIlFileTenutoApertoDaUnAltroHandle_aspettaETogliePoi() {
    val target = file()
    target.writeText("1")
    val other = FileInputStream(target)
    val releasing = ReleasingPatience(other)

    val gone = other.use { StateFiles.delete(target, patience = releasing.patience) }

    assertThat(gone).isTrue()
    assertThat(target.exists()).isFalse()
    if (windows) assertThat(releasing.waits).isAtLeast(1)
  }

  @Test
  fun cancella_seIlFileResta_dice_falso_eNonLoFaCredereTolto() {
    // Su Windows un handle che non si chiude mai; altrove una cartella non vuota: in tutti e due i casi il file/nome e' ancora li'.
    val target = file()
    target.writeText("1")
    val other: FileInputStream? = if (windows) FileInputStream(target) else null
    if (!windows) {
      target.delete()
      target.mkdirs()
      File(target, "dentro").writeText("1")
    }
    val sink = RecordingSink()
    GalleryLog.install(sink)

    val gone = try {
      StateFiles.delete(target, patience = Patience(listOf(1L, 1L)) { })
    } finally {
      other?.close()
    }

    assertThat(gone).isFalse()
    assertThat(target.exists()).isTrue()
    assertThat(sink.lines.filter { it.first == LogLevel.WARN }.map { it.second }.joinToString("\n")).contains("stato")
  }

  @Test
  fun cancella_unaLetturaDiStateFilesNonImpediscePiuLaCancellazione() {
    // `readBytes` apre, legge e chiude: non lascia handle aperti dietro di se'.
    val target = file()
    StateFiles.writeText(target, "1")
    repeat(20) { assertThat(StateFiles.readText(target)).isEqualTo("1") }

    assertThat(StateFiles.delete(target, patience = Patience.NONE)).isTrue()
  }

  @Test
  fun cancella_toglieAncheIlTemporaneo() {
    // R11: un `<nome>.tmp` rimasto (processo morto a meta' scrittura) puo' avere il contenuto che si voleva togliere.
    val target = file()
    target.writeText("1")
    val leftover = File(temp.root, "stato.tmp").also { it.writeText("nascosto") }

    assertThat(StateFiles.delete(target)).isTrue()

    assertThat(target.exists()).isFalse()
    assertThat(leftover.exists()).isFalse()
  }

  @Test
  fun cancella_senzaIlFileMaConIlTemporaneo_toglieIlTemporaneo() {
    val leftover = File(temp.root, "stato.tmp").also { it.writeText("nascosto") }

    assertThat(StateFiles.delete(file())).isTrue()

    assertThat(leftover.exists()).isFalse()
  }

  @Test
  fun cancella_aspettaUnaScritturaInCorsoENonLaFaRinascere() {
    // R11: `delete` non prendeva il lucchetto di `write`: se passava mentre una scrittura era a meta', il file nasceva un istante dopo.
    val target = file()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val writer = thread {
      StateFiles.write(target) { out ->
        out.write("nuovo".toByteArray())
        started.countDown()
        release.await()
      }
    }
    started.await()
    val deleted = AtomicBoolean(false)
    val deleter = thread { deleted.set(StateFiles.delete(target)) }
    // Senza orologio: il thread della cancellazione o si ferma sul lucchetto della scrittura, o (senza il lucchetto) finisce subito.
    while (deleter.state != Thread.State.BLOCKED && deleter.state != Thread.State.TERMINATED) Thread.onSpinWait()
    release.countDown()
    writer.join()
    deleter.join()

    assertThat(deleted.get()).isTrue()
    assertThat(target.exists()).isFalse()
    assertThat(File(temp.root, "stato.tmp").exists()).isFalse()
  }

  // --- thread interrotto, tetto di lettura (I10) ------------------------------------------------------------------------------

  @Test
  fun scrive_conIlThreadInterrotto_riesceERimetteIlSegno() {
    // Un canale di java.nio su un thread interrotto si chiude con ClosedByInterruptException: lo stato non si salvava mentre il lavoro veniva fermato.
    val target = file()
    Thread.currentThread().interrupt()

    StateFiles.writeText(target, "salvato")

    assertThat(Thread.currentThread().isInterrupted).isTrue()
    Thread.interrupted()
    assertThat(target.readText()).isEqualTo("salvato")
  }

  @Test
  fun legge_conIlThreadInterrotto_riesceERimetteIlSegno() {
    val target = file()
    target.writeText("letto")
    Thread.currentThread().interrupt()

    val text = StateFiles.readText(target)

    assertThat(Thread.currentThread().isInterrupted).isTrue()
    Thread.interrupted()
    assertThat(text).isEqualTo("letto")
  }

  @Test
  fun marcaECancella_conIlThreadInterrotto_riescono() {
    val target = file()
    Thread.currentThread().interrupt()

    StateFiles.mark(target)
    val marked = target.exists()
    val gone = StateFiles.delete(target)

    assertThat(Thread.currentThread().isInterrupted).isTrue()
    Thread.interrupted()
    assertThat(marked).isTrue()
    assertThat(gone).isTrue()
    assertThat(target.exists()).isFalse()
  }

  @Test
  fun legge_unFilePiuGrandeDelTettoLanciaSenzaLeggerlo() {
    val target = file()
    target.writeBytes(ByteArray(64))

    val error = runCatching { StateFiles.readBytes(target, maxBytes = 63) }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    // Esattamente al tetto si legge, e un file piccolo non e' toccato.
    assertThat(StateFiles.readBytes(target, maxBytes = 64)!!.size).isEqualTo(64)
  }

  @Test
  fun legge_unFileTroppoGrandeNonSiRiprova() {
    val target = file()
    target.writeBytes(ByteArray(64))
    val waited = ArrayList<Long>()

    runCatching { StateFiles.readBytes(target, patience = Patience(listOf(1L, 2L)) { waited += it }, maxBytes = 10) }

    assertThat(waited).isEmpty()
  }

  // --- lettura, marche, aggiunte ----------------------------------------------------------------------------------------------

  @Test
  fun legge_unFileCheNonCeEnull() {
    assertThat(StateFiles.readBytes(file("manca"))).isNull()
    assertThat(StateFiles.readText(file("manca"))).isNull()
  }

  @Test
  fun marca_faEsistereUnFileVuotoEPuoEssereRipetuta() {
    val target = File(temp.root, "x/marca")

    StateFiles.mark(target)
    StateFiles.mark(target)

    assertThat(target.isFile).isTrue()
    assertThat(target.length()).isEqualTo(0L)
    assertThat(StateFiles.delete(target)).isTrue()
    assertThat(target.exists()).isFalse()
  }

  @Test
  fun aggiunge_inCodaECreaIlFile() {
    val target = File(temp.root, "x/registro")

    StateFiles.append(target, "uno\n", Charsets.US_ASCII)
    StateFiles.append(target, "due\n", Charsets.US_ASCII)

    assertThat(target.readText()).isEqualTo("uno\ndue\n")
  }

  private companion object {
    /** Un tetto largo (circa un minuto di millisecondi): serve solo a non restare appesi se la scrittura non riesce davvero mai. */
    const val MAX_ATTEMPTS = 60_000
  }
}
