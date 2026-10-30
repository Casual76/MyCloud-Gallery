package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.contract.ShelfRead
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Come [FileShelfStore] tocca il disco: un posto solo per le tre operazioni, cosi' le prove sostituiscono **solo** questo (la morte del
 * processo a meta' scrittura, un file che non si legge) e provano il resto con il codice vero. Quello di serie e' [StateFilesIo].
 */
internal interface ShelfIo {
  /** I byte del file, o null se non c'e'. Lancia `IOException` se c'e' ma non si legge. */
  fun read(file: File): ByteArray?

  /** Scrive il file in modo atomico: chi legge dopo vede quello di prima intero o quello nuovo intero. */
  fun write(file: File, bytes: ByteArray)

  /** Toglie il file e il suo temporaneo; falso se ne resta uno. */
  fun delete(file: File): Boolean
}

/** I file di stato di tutta l'app (`StateFiles`): temporaneo nella stessa cartella, `fsync`, rinomina atomica; mai un percorso nel log. */
internal object StateFilesIo : ShelfIo {
  override fun read(file: File): ByteArray? = StateFiles.readBytes(file, LABEL, maxBytes = ShelfCodec.MAX_BYTES.toLong() + 1)

  override fun write(file: File, bytes: ByteArray) = StateFiles.writeBytes(file, bytes, LABEL)

  override fun delete(file: File): Boolean = StateFiles.delete(file, LABEL)
}

private const val LABEL = "shelf.bin"

/**
 * Lo scaffale dei ricordi su file (docs/fase9-progetto.md D9, D10, §2.3; modello-dati.md §16): `noBackupFilesDir/memories/shelf.bin`, mai sul
 * NAS, mai nei backup, tolto dall'azzeramento (D37).
 *
 * - **Letto in un colpo**: [read] legge il file intero e lo decodifica ([ShelfCodec]); Raccolte -> Ricordi si disegna subito dall'ultimo
 *   scaffale (principio "Avvio e pagine istantanei").
 * - **Scritto in modo atomico** ([StateFiles]: temporaneo accanto al file, `fsync`, rinomina): la morte del processo a meta' scrittura lascia
 *   il file di prima, intero; un temporaneo lasciato a meta' si sovrascrive alla scrittura dopo e non si legge mai.
 * - **File rovinato = scaffale vuoto**: troncato, byte cambiati, CRC sbagliato, versione diversa o troppo grande risponde [ShelfRead.Corrupt]
 *   (il servizio rigenera e lo dice: `MEMORY_SHELF_RESET`), mai un crollo. Un errore del disco (il file c'e' ma non si legge) e' invece
 *   un'eccezione: lo scaffale buono non si butta per un guasto passeggero.
 * - I tre metodi lavorano su [dispatcher] (di serie `Dispatchers.IO`): le funzioni di `StateFiles` bloccano.
 *
 * Nel log, via `StateFiles`, solo il nome fisso del file e il tipo dell'errore: mai un percorso, mai un titolo o un id.
 */
class FileShelfStore internal constructor(
  private val directory: File,
  private val io: ShelfIo,
  private val dispatcher: CoroutineDispatcher,
) : MemoryShelfStore {

  /** [directory] e' `noBackupFilesDir/memories`: il file e' `shelf.bin`. */
  constructor(directory: File, dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(directory, StateFilesIo, dispatcher)

  private val target: File get() = File(directory, FILE_NAME)

  override suspend fun read(): ShelfRead = withContext(dispatcher) {
    val file = target
    // Un file oltre il tetto non si carica in memoria: e' rovinato (lo dice anche `ShelfCodec`), e il servizio lo rigenera.
    if (file.isFile && file.length() > ShelfCodec.MAX_BYTES) return@withContext ShelfRead.Corrupt
    val bytes = io.read(file) ?: return@withContext ShelfRead.Missing
    ShelfCodec.decode(bytes)
  }

  override suspend fun write(file: ShelfFile) {
    val bytes = ShelfCodec.encode(file)
    withContext(dispatcher) { io.write(target, bytes) }
  }

  /** Toglie lo scaffale e il suo temporaneo; se non ci riesce lo dice (l'azzeramento deve saperlo): `IOException`. */
  override suspend fun clear() {
    val gone = withContext(dispatcher) { io.delete(target) }
    if (!gone) throw IOException("scaffale dei ricordi non tolto")
  }

  companion object {
    const val FILE_NAME: String = "shelf.bin"
  }
}
