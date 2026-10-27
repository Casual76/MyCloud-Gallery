package dev.pampa.mycloudgallery.core.common.files

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Quante volte e con che attese si riprova un'operazione su un file di stato che e' fallita ([StateFiles]).
 *
 * Il caso vero e' Windows (e quindi i test sulla JVM): `java.io` apre i file senza `FILE_SHARE_DELETE`, e finche' un altro handle e' aperto
 * (una lettura in corso, un antivirus, un indicizzatore) `File.delete()` e `File.renameTo()` falliscono **in silenzio**, restituendo `false`.
 * Quasi sempre l'handle si chiude dopo pochi millisecondi: aspettare e riprovare basta. Su Android (semantica POSIX) un file aperto non
 * impedisce di cancellarlo o di rinominarvi sopra, quindi li' non si riprova mai e l'attesa non costa niente.
 *
 * @property waitsMs le attese prima del secondo tentativo, del terzo e cosi' via: [STANDARD] ne ha cinque, quindi sei tentativi in circa
 * 310 ms. Vuota = un solo tentativo.
 * @property pause come si aspetta: di norma `Thread.sleep`. Una prova ci mette dentro "chiudo l'handle che tiene il file", e cosi' non
 * dipende dall'orologio.
 */
class Patience(val waitsMs: List<Long>, val pause: (Long) -> Unit = { Thread.sleep(it) }) {

  init {
    require(waitsMs.all { it >= 0 }) { "attesa negativa" }
  }

  companion object {
    /** Sei tentativi in circa 310 ms. */
    val STANDARD = Patience(listOf(10L, 20L, 40L, 80L, 160L))

    /** Un solo tentativo. */
    val NONE = Patience(emptyList())
  }
}

/**
 * Lettura, scrittura e cancellazione dei **file di stato locali** (memorie dei giri, battiti, preferenze, marche, file di `noBackupFilesDir`)
 * con `java.nio.file.Files`: cio' che prima si faceva con `File.delete()`, `File.renameTo()` e `File.writeText()`, che dicono "non e'
 * riuscito" con un `false` che quasi nessuno guardava.
 *
 * - **Scrittura atomica** ([writeBytes], [writeText]; [write] per chi scrive a pezzi, [replace] per chi si fa il temporaneo da solo): si
 *   scrive su `<nome>.tmp` nella **stessa cartella**, si forza su disco, poi `Files.move` con `ATOMIC_MOVE` e `REPLACE_EXISTING`. Chi legge
 *   vede il file vecchio intero o quello nuovo intero, mai un file a meta' e mai un file che manca (`File.renameTo` su Windows non
 *   sostituisce un file che c'e' gia': il vecchio codice lo cancellava prima, e in quell'istante il file non esisteva). Due scritture dello
 *   stesso file nello stesso processo si accodano (il temporaneo e' uno solo).
 * - **Cancellazione verificata** ([delete]): `Files.deleteIfExists`, che dice la verita' (un errore e' un'eccezione, non un `false`). Vero
 *   **solo** se il file non c'e' piu'; se non si riesce si riprova ([Patience]) e poi si dice di no. Chi riceve `false` sa che il file e'
 *   ancora li', e non deve comportarsi come se non ci fosse.
 * - **Lettura breve** ([readBytes], [readText]): `Files.readAllBytes` apre, legge e chiude subito, e (su Windows) lascia che un altro
 *   thread cancelli il file mentre lo si legge.
 *
 * **Un'operazione fallita non sparisce mai in silenzio**: un tentativo andato male e riprovato finisce nel log di debug; se alla fine non
 * riesce, finisce nel log (`GalleryLog`, livello warn) e chi chiama lo sa: la scrittura lancia `IOException`, la cancellazione dice `false`.
 * Nel log c'e' solo il nome del file di stato (o la [label] che il chiamante sceglie quando il nome contiene un id) e il tipo dell'errore:
 * mai un percorso, mai il contenuto. Il messaggio dell'eccezione e' fatto allo stesso modo (il testo del sistema, che ha il percorso, non
 * viaggia).
 *
 * Le funzioni **bloccano** (le attese sono `Thread.sleep`): da un dispatcher di I/O, come ogni lettura di file.
 *
 * Non per i file dei media ne' per il NAS: quei percorsi hanno i loro diari e le loro verifiche.
 */
object StateFiles {

  private const val TAG = "StateFiles"
  private const val TEMP_SUFFIX = ".tmp"
  private const val STRIPES = 32

  /** Una scrittura per volta per ogni file (per striscia: pochi lucchetti, mai un elenco che cresce con i file). */
  private val writeLocks = Array(STRIPES) { Any() }

  /** I byte di [file], o null se non c'e'. Lancia `IOException` se c'e' ma non si riesce a leggerlo (dopo i tentativi di [patience]). */
  fun readBytes(file: File, label: String = file.name, patience: Patience = Patience.STANDARD): ByteArray? {
    val path = file.toPath()
    return retrying("lettura", label, patience) {
      try {
        Files.readAllBytes(path)
      } catch (_: NoSuchFileException) {
        null
      }
    }
  }

  /** Il testo di [file] (come [readBytes]), o null se non c'e'. */
  fun readText(
    file: File,
    charset: Charset = Charsets.UTF_8,
    label: String = file.name,
    patience: Patience = Patience.STANDARD,
  ): String? = readBytes(file, label, patience)?.toString(charset)

  /** Le righe di [file] (come `File.readLines`: nessuna riga vuota in fondo per l'ultimo a capo), o null se non c'e'. */
  fun readLines(
    file: File,
    charset: Charset = Charsets.UTF_8,
    label: String = file.name,
    patience: Patience = Patience.STANDARD,
  ): List<String>? = readText(file, charset, label, patience)?.reader()?.readLines()

  /**
   * Scrive [file] riempiendo lo stream che [body] riceve, in modo atomico (vedi la descrizione di [StateFiles]); crea le cartelle che mancano.
   * Lo stream non e' bufferizzato (chi scrive molti pezzi piccoli ci mette un `BufferedOutputStream`), e chiuderlo non fa niente: lo chiude
   * questa funzione, dopo aver forzato il temporaneo su disco. [durable]: lo forza (di norma si', e' cio' che evita un file vuoto dopo
   * un'interruzione di corrente); si spegne solo per un file riscritto di continuo e che si puo' perdere.
   *
   * @throws IOException se non riesce nemmeno dopo i tentativi di [patience]: il file di prima, se c'era, e' intatto. Un'eccezione di [body]
   * passa com'e', e il temporaneo non resta.
   */
  fun write(
    file: File,
    label: String = file.name,
    durable: Boolean = true,
    patience: Patience = Patience.STANDARD,
    body: (OutputStream) -> Unit,
  ) {
    val target = file.absoluteFile
    val directory = target.parentFile ?: throw IOException("scrittura non riuscita: $label (nessuna cartella)")
    synchronized(lockOf(target)) {
      val temp = File(directory, target.name + TEMP_SUFFIX).toPath()
      var moved = false
      try {
        try {
          Files.createDirectories(directory.toPath())
          FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val stream = KeepOpen(Channels.newOutputStream(channel))
            body(stream)
            stream.flush()
            if (durable) channel.force(true)
          }
        } catch (e: IOException) {
          throw failure("scrittura", label, e)
        }
        retrying("rinomina", label, patience) { moveOver(temp, target.toPath()) }
        moved = true
      } finally {
        // Il temporaneo non resta: un file a meta' vicino a quello vero e' proprio cio' che questa funzione vuole evitare.
        if (!moved) {
          try {
            Files.deleteIfExists(temp)
          } catch (_: IOException) {
            GalleryLog.w(TAG) { "temporaneo non tolto: $label" }
          }
        }
      }
    }
  }

  /** Come [write], per dei byte gia' pronti. */
  fun writeBytes(
    file: File,
    bytes: ByteArray,
    label: String = file.name,
    durable: Boolean = true,
    patience: Patience = Patience.STANDARD,
  ) = write(file, label, durable, patience) { it.write(bytes) }

  /** Come [writeBytes], per un testo. */
  fun writeText(
    file: File,
    text: String,
    charset: Charset = Charsets.UTF_8,
    label: String = file.name,
    durable: Boolean = true,
    patience: Patience = Patience.STANDARD,
  ) = writeBytes(file, text.toByteArray(charset), label, durable, patience)

  /**
   * Mette [from] al posto di [to] con una rinomina atomica (`ATOMIC_MOVE` + `REPLACE_EXISTING`), per chi il temporaneo se lo scrive da solo
   * (un registro con il suo formato, un file che si riempie in un altro modo): `File.renameTo` non sostituisce su Windows, e il vecchio
   * ripiego "cancella e riprova" lasciava un istante senza il file. Se non riesce lancia `IOException`; [from] resta dov'e' (lo toglie il chiamante).
   */
  fun replace(from: File, to: File, label: String = to.name, patience: Patience = Patience.STANDARD) {
    val target = to.absoluteFile
    synchronized(lockOf(target)) {
      retrying("rinomina", label, patience) { moveOver(from.absoluteFile.toPath(), target.toPath()) }
    }
  }

  /**
   * Aggiunge [text] in coda a [file] (lo crea se manca). Non e' atomico, ed e' giusto cosi' per un registro a righe: chi lo legge scarta la
   * riga a meta'. Un errore e' un'`IOException` (e un rigo nel log), non un `false`.
   */
  fun append(file: File, text: String, charset: Charset = Charsets.UTF_8, label: String = file.name) {
    try {
      val directory = file.absoluteFile.parentFile
      if (directory != null) Files.createDirectories(directory.toPath())
      Files.write(file.toPath(), text.toByteArray(charset), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch (e: IOException) {
      throw failure("aggiunta", label, e)
    }
  }

  /**
   * Fa esistere [file] vuoto (una "marca": c'e' = acceso), se non c'e' gia'; crea le cartelle che mancano.
   *
   * @throws IOException se non riesce nemmeno dopo i tentativi di [patience].
   */
  fun mark(file: File, label: String = file.name, patience: Patience = Patience.STANDARD) {
    val path = file.absoluteFile.toPath()
    retrying("marca", label, patience) {
      path.parent?.let { Files.createDirectories(it) }
      try {
        Files.createFile(path)
      } catch (_: FileAlreadyExistsException) {
        // C'e' gia': e' cio' che si voleva.
      }
    }
  }

  /**
   * Toglie [file]. **Vero solo se non c'e' piu'** (non c'era, o e' stato tolto); falso se c'e' ancora, dopo i tentativi di [patience] e un rigo
   * nel log. Non lancia mai: chi vuole un'eccezione la lancia da se' su `false`.
   */
  fun delete(file: File, label: String = file.name, patience: Patience = Patience.STANDARD): Boolean {
    val path = file.toPath()
    return try {
      retrying("cancellazione", label, patience) { Files.deleteIfExists(path) }
      true
    } catch (_: IOException) {
      false
    }
  }

  // --- pezzi -------------------------------------------------------------------------------------------------------------------

  private fun lockOf(target: File): Any = writeLocks[(target.path.hashCode() and Int.MAX_VALUE) % STRIPES]

  /** Lo stream passato a chi scrive: se lo chiude (un `use`, un writer) non chiude il canale, che va ancora forzato su disco. */
  private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
    }

    override fun close() {
      flush()
    }
  }

  private fun moveOver(temp: Path, target: Path) {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      // Un file system che non sa farlo (non succede nella stessa cartella di noBackupFilesDir): si sostituisce in due tempi.
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /**
   * Esegue [block]; se lancia un'`IOException` che ha senso riprovare, aspetta e riprova secondo [patience]. Alla fine lancia
   * l'errore **senza** il testo del sistema (porta il percorso): vedi [failure]. Non si riprova quando e' inutile (il file non c'e', la
   * cartella non e' vuota: aspettare non cambia niente).
   */
  private fun <T> retrying(what: String, label: String, patience: Patience, block: () -> T): T {
    var attempt = 0
    while (true) {
      try {
        return block()
      } catch (e: IOException) {
        val wait = patience.waitsMs.getOrNull(attempt)
        if (wait == null || e is NoSuchFileException || e is DirectoryNotEmptyException) throw failure(what, label, e, attempt + 1)
        GalleryLog.d(TAG) { "$what non riuscita (${e.javaClass.simpleName}), nuovo tentativo: $label" }
        attempt++
        try {
          patience.pause(wait)
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw failure(what, label, e, attempt)
        }
      }
    }
  }

  /** L'errore definitivo: nel log (nome del file di stato e tipo dell'errore), e come eccezione senza percorsi. */
  private fun failure(what: String, label: String, cause: IOException, attempts: Int = 1): IOException {
    val reason = cause.javaClass.simpleName
    GalleryLog.w(TAG) { "$what non riuscita ($reason, tentativi: $attempts): $label" }
    return IOException("$what del file di stato non riuscita ($reason): $label")
  }
}
