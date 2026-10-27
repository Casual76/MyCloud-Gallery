package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode
import java.io.File
import java.io.IOException

// IL DIARIO DI "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §2.8 "I passi sul dispositivo che cancella"). Un file `faces-erase` in
// `noBackupFilesDir`, **fuori** dal database dei volti (che la cancellazione toglie), scritto con `fsync` e in modo atomico (`StateFiles`) prima di
// ogni passo e dopo ognuno: una morte del processo riprende dal primo passo non segnato, e ogni passo e' idempotente. Il diario ricorda generazioni:
// mai nei log, mai in Diagnostica (D37).

/** Lo stato di un passo che si puo' anche saltare dopo i tentativi (album e cronologia: servono i dati, non devono tenere in ostaggio la cancellazione). */
enum class ErasePart(override val code: String) : StableCode {
  PENDING("pending"),
  DONE("done"),

  /** Tre tentativi falliti: si va avanti senza (detto nella sezione di P8 di `docs/decisioni.md`, limite L190). */
  SKIPPED("skipped"),
}

/**
 * Il diario di una cancellazione: la generazione [generation] (con [earlier], le generazioni di una cancellazione precedente non ancora finita sul
 * NAS: questa le assorbe), la scelta sugli album, l'impronta della conferma, se Persone era solo su questo dispositivo ([localOnly], D34: niente sul
 * NAS) e i passi fatti. [doneAtNas]: l'ora del NAS della fine (il diario resta 30 giorni per la pagina, poi si toglie).
 */
data class EraseJournal(
  val generation: FaceGeneration,
  val earlier: List<FaceGeneration> = emptyList(),
  val choice: PeopleAlbumsChoice,
  val digest: Long,
  val localOnly: Boolean,
  val stopped: Boolean = false,
  val albums: ErasePart = ErasePart.PENDING,
  val history: ErasePart = ErasePart.PENDING,
  val setting: Boolean = false,
  val local: Boolean = false,
  val announced: Boolean = false,
  val nasDone: Boolean = false,
  val verified: Boolean = false,
  val confirmed: Boolean = false,
  val done: Boolean = false,
  val recycleBin: Boolean = false,
  /** L'ora del NAS della voce `off` uscita con la propria testa (passo 7): le teste degli altri confermano solo se non sono piu' vecchie. */
  val offAtNas: Long? = null,
  val doneAtNas: Long? = null,
) {
  /** Tutte le generazioni che questa cancellazione toglie. */
  val erased: List<FaceGeneration> get() = (listOf(generation) + earlier).distinct()

  /** La parte locale e' finita (passi 1-6): i dati di questo dispositivo sono tolti. */
  val localFinished: Boolean get() = local && albums != ErasePart.PENDING && history != ErasePart.PENDING

  /** L'ultimo passo del §2.8 fatto senza buchi prima (per la pagina e la Diagnostica). */
  val step: EraseStep
    get() {
      val reached = listOf(
        EraseStep.STOPPED to stopped,
        EraseStep.ALBUMS to (albums != ErasePart.PENDING),
        EraseStep.HISTORY to (history != ErasePart.PENDING),
        EraseStep.SETTING to setting,
        EraseStep.LOCAL_DONE to local,
        EraseStep.ANNOUNCED to (announced || localOnly),
        EraseStep.NAS_DONE to (nasDone || localOnly),
        EraseStep.VERIFIED to (verified || localOnly),
        EraseStep.CONFIRMED to (confirmed || localOnly),
        EraseStep.DONE to done,
      )
      var step = EraseStep.STARTED
      for ((candidate, ok) in reached) {
        if (!ok) break
        step = candidate
      }
      return step
    }

  override fun toString(): String = "EraseJournal(${step.code}, generazioni=${erased.size}, locale=$localOnly)"
}

/** Cosa si e' trovato nel file del diario. */
sealed interface EraseJournalRead {
  data object Absent : EraseJournalRead

  class Present(val journal: EraseJournal) : EraseJournalRead {
    override fun toString(): String = "EraseJournalRead.Present"
  }

  /** C'e' ma non si legge (rovinato, di un'altra versione): chi cancella ricostruisce dal segno `FacesLocal.erasing`. */
  data object Unreadable : EraseJournalRead
}

/** Dove sta il diario. [save] e' atomico e durevole prima di tornare, o lancia `IOException` (il diario di prima resta intero). */
interface EraseJournalStore {
  fun load(): EraseJournalRead

  fun save(journal: EraseJournal)

  /** Toglie il diario; vero se non c'e' piu'. */
  fun clear(): Boolean
}

/** Il diario vero, nel file [file] (`noBackupFilesDir/faces-erase`), con [StateFiles]. */
class FileEraseJournalStore(private val file: File) : EraseJournalStore {

  override fun load(): EraseJournalRead {
    val text = try {
      StateFiles.readText(file, label = FILE)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "diario della cancellazione non letto (${e.javaClass.simpleName})" }
      return EraseJournalRead.Unreadable
    } ?: return EraseJournalRead.Absent
    return EraseJournalCodec.decode(text)?.let { EraseJournalRead.Present(it) } ?: EraseJournalRead.Unreadable
  }

  override fun save(journal: EraseJournal) = StateFiles.writeText(file, EraseJournalCodec.encode(journal), label = FILE)

  override fun clear(): Boolean = StateFiles.delete(file, label = FILE)

  override fun toString(): String = "FileEraseJournalStore"

  companion object {
    /** Il nome del file in `noBackupFilesDir` (l'azzeramento lo toglie con gli altri file locali, P13). */
    const val FILE: String = "faces-erase"

    private const val TAG = "FacesErase"
  }
}

/** La forma testo del diario: una riga `chiave=valore` per campo, tutti presenti, nessun campo sconosciuto (un diario strano non si indovina). */
object EraseJournalCodec {
  private const val MAGIC = "mcg-faces-erase 1"

  fun encode(j: EraseJournal): String = buildString {
    append(MAGIC).append('\n')
    line("generation", j.generation.base32)
    line("earlier", j.earlier.joinToString(",") { it.base32 })
    line("choice", j.choice.code)
    line("digest", j.digest.toString())
    line("localOnly", flag(j.localOnly))
    line("stopped", flag(j.stopped))
    line("albums", j.albums.code)
    line("history", j.history.code)
    line("setting", flag(j.setting))
    line("local", flag(j.local))
    line("announced", flag(j.announced))
    line("nasDone", flag(j.nasDone))
    line("verified", flag(j.verified))
    line("confirmed", flag(j.confirmed))
    line("done", flag(j.done))
    line("recycleBin", flag(j.recycleBin))
    line("offAtNas", j.offAtNas?.toString().orEmpty())
    line("doneAtNas", j.doneAtNas?.toString().orEmpty())
  }

  /** Il diario di [text], o null se non e' un diario di questa versione scritto per intero. */
  fun decode(text: String): EraseJournal? = try {
    val lines = text.lines().filter { it.isNotEmpty() }
    require(lines.firstOrNull() == MAGIC)
    val fields = HashMap<String, String>()
    for (line in lines.drop(1)) {
      val eq = line.indexOf('=')
      require(eq > 0)
      require(fields.put(line.substring(0, eq), line.substring(eq + 1)) == null)
    }
    require(fields.keys == KEYS)
    fun bool(key: String): Boolean = when (fields.getValue(key)) {
      "1" -> true
      "0" -> false
      else -> throw IllegalArgumentException()
    }
    fun part(key: String): ErasePart = ErasePart.entries.first { it.code == fields.getValue(key) }
    val earlier = fields.getValue("earlier").takeIf { it.isNotEmpty() }?.split(',')?.map { requireNotNull(FaceGeneration.parse(it)) }.orEmpty()
    EraseJournal(
      generation = requireNotNull(FaceGeneration.parse(fields.getValue("generation"))),
      earlier = earlier,
      choice = PeopleAlbumsChoice.entries.first { it.code == fields.getValue("choice") },
      digest = fields.getValue("digest").toLong(),
      localOnly = bool("localOnly"),
      stopped = bool("stopped"),
      albums = part("albums"),
      history = part("history"),
      setting = bool("setting"),
      local = bool("local"),
      announced = bool("announced"),
      nasDone = bool("nasDone"),
      verified = bool("verified"),
      confirmed = bool("confirmed"),
      done = bool("done"),
      recycleBin = bool("recycleBin"),
      offAtNas = fields.getValue("offAtNas").takeIf { it.isNotEmpty() }?.toLong(),
      doneAtNas = fields.getValue("doneAtNas").takeIf { it.isNotEmpty() }?.toLong(),
    )
  } catch (e: IllegalArgumentException) {
    null
  } catch (e: NoSuchElementException) {
    null
  }

  private val KEYS = setOf(
    "generation", "earlier", "choice", "digest", "localOnly", "stopped", "albums", "history", "setting", "local", "announced", "nasDone",
    "verified", "confirmed", "done", "recycleBin", "offAtNas", "doneAtNas",
  )

  private fun StringBuilder.line(key: String, value: String) {
    append(key).append('=').append(value).append('\n')
  }

  private fun flag(value: Boolean) = if (value) "1" else "0"
}
