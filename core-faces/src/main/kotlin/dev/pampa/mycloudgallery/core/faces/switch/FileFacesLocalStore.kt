package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo stato **locale** di Persone su questo dispositivo, nel file `faces-local` di `noBackupFilesDir` (D4, §2.1; mai una voce dei dati personali,
 * mai un backup): l'esclusione, l'avviso gia' visto, la generazione dei dati locali, una cancellazione in corso ([FacesLocal]).
 *
 * - **Scrittura atomica e verificata** ([StateFiles]): il file di prima o quello nuovo, intero. Un file di soli valori di serie non si scrive
 *   (e quello che c'era si toglie): non rinasce dopo l'azzeramento e "niente file" vuol dire "tutto di serie".
 * - **Il file e' piu' vecchio della memoria solo se la scrittura non e' riuscita** (disco pieno, file tenuto aperto): [update] non lancia, la
 *   memoria resta quella nuova (vale per questo processo, e l'esclusione non si perde per un file) e ci si riprova a ogni [update] e con [flush];
 *   [unsaved] lo dice. Al riavvio vale il file: se l'esclusione o la generazione dei dati non sono arrivate al disco, l'avvio le riconcilia con la
 *   voce dell'account e con i dati che ci sono.
 * - **Nel dubbio, di serie**: file assente, di un'altra versione o rovinato = [FacesLocal.EMPTY] (non escluso, nessun avviso visto, nessun dato
 *   ricordato). Per Persone e' il lato prudente: senza avviso visto un'attivazione degli altri dispositivi chiede di nuovo l'avviso e niente si
 *   tratta prima.
 * - Il file ricorda generazioni: nessun log, nessuna Diagnostica (D37); `toString()` di [FacesLocal] non mostra valori.
 * - [reset] lo toglie (azzeramento dei dati dell'account): lo chiama chi azzera, dopo aver fermato l'interruttore.
 */
class FileFacesLocalStore(private val file: File) : FacesLocalStore {

  private val lock = Mutex()
  private val state = MutableStateFlow(read())

  override val local: StateFlow<FacesLocal> = state

  /** Il file non dice cio' che la memoria sa: l'ultima scrittura (o cancellazione) non e' riuscita. */
  @Volatile var unsaved: Boolean = false
    private set

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal = lock.withLock {
    val next = transform(state.value)
    if (next != state.value || unsaved) {
      state.value = next
      write(next)
    }
    next
  }

  /** Riprova a scrivere cio' che non e' arrivato al disco. Vero quando il file dice cio' che la memoria sa. */
  suspend fun flush(): Boolean = lock.withLock {
    if (unsaved) write(state.value)
    !unsaved
  }

  /** Dimentica tutto e toglie il file (azzeramento): il prossimo account parte da zero. */
  suspend fun reset() = lock.withLock {
    state.value = FacesLocal.EMPTY
    write(FacesLocal.EMPTY)
    StateFiles.delete(File(file.parentFile, file.name + ".tmp"), label = file.name + ".tmp")
  }

  private fun write(value: FacesLocal) {
    if (value == FacesLocal.EMPTY) {
      unsaved = !StateFiles.delete(file)
      return
    }
    try {
      StateFiles.writeText(file, encode(value))
      unsaved = false
    } catch (e: IOException) {
      unsaved = true
    }
  }

  private fun read(): FacesLocal {
    val lines = try {
      StateFiles.readLines(file)
    } catch (e: IOException) {
      null
    } ?: return FacesLocal.EMPTY
    return decode(lines) ?: FacesLocal.EMPTY
  }

  companion object {
    /** Il nome del file in `noBackupFilesDir` (lo toglie l'azzeramento dei dati dell'account). */
    const val FILE = "faces-local"
    private const val FORMAT = "1"
    private const val NONE = "-"

    internal fun encode(value: FacesLocal): String = listOf(
      FORMAT,
      if (value.excluded) "1" else "0",
      value.noticedGeneration?.base32 ?: NONE,
      (value.noticedTextVersion ?: 0).toString(),
      value.dataGeneration?.base32 ?: NONE,
      value.erasing?.base32 ?: NONE,
    ).joinToString("\n", postfix = "\n")

    /** Null = file che non si capisce. */
    internal fun decode(lines: List<String>): FacesLocal? {
      if (lines.size < 6 || lines[0] != FORMAT) return null
      val excluded = when (lines[1]) {
        "1" -> true
        "0" -> false
        else -> return null
      }
      val noticed = generationOf(lines[2]) ?: return null
      val version = lines[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val data = generationOf(lines[4]) ?: return null
      val erasing = generationOf(lines[5]) ?: return null
      return FacesLocal(
        excluded = excluded,
        noticedGeneration = noticed.value,
        noticedTextVersion = if (noticed.value != null && version > 0) version else null,
        dataGeneration = data.value,
        erasing = erasing.value,
      )
    }

    /** "-" = nessuna generazione; una generazione non canonica = il file non si capisce. */
    private fun generationOf(text: String): Optional? = when {
      text == NONE -> Optional(null)
      else -> FaceGeneration.parse(text)?.let { Optional(it) }
    }

    private class Optional(val value: FaceGeneration?)
  }
}
