package dev.pampa.mycloudgallery.core.personal.sync

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * COSA DICE L'ULTIMA TESTA PERSONALE DI OGNI DISPOSITIVO DELL'ACCOUNT (Fase 8, P8; docs/fase8-progetto.md D30, §2.8 passo 10; `PersonalHeadsView`).
 * La scrive [NasPersonalSync] mentre legge le teste (e dopo aver pubblicato la propria): per ogni dispositivo l'ora del NAS della testa e le voci
 * delle chiavi **guardate** ([watched], di serie la sola voce `faces`) che quella testa contiene. "Disattiva e cancella" la legge per dire chi ha
 * cancellato: una testa con `faces` = `off|G` esiste solo dopo che quel dispositivo ha tolto i dati di `G` (il gancio `PrePublishHook`).
 *
 * - **Si ricorda solo una testa letta per intero** (le voci di una testa letta a meta' non dicono cosa manca).
 * - **Durevole** se c'e' [file] (scrittura atomica con [StateFiles]): dopo la morte del processo il motore non rilegge le teste gia' note (le
 *   riconosce dal segno anti-rollback), quindi cio' che dicevano deve restare. [record] risponde falso se il file non si scrive: chi chiama
 *   **non** ricorda il segno di quella testa e la rilegge al giro dopo. Senza [file] (prove) vive in memoria.
 * - **Nel dubbio, niente**: un file rovinato o di un'altra versione vale "nessuna testa nota" (nessun dispositivo ha confermato: la pagina
 *   aspetta), mai "tutti hanno cancellato". Una chiave non guardata risponde "nessuna testa nota".
 * - Mai valori, id o generazioni nei log (D37): solo il nome fisso del file.
 */
class PersonalHeadsRecord(
  private val file: File? = null,
  private val watched: Set<PersonalKey> = setOf(FacesSettingCodec.KEY),
) : PersonalHeadsView {

  private class Recorded(val headAtNas: Long, val entries: Map<PersonalKey, PersonalEntry>)

  private val lock = Any()
  private val heads: MutableMap<String, Recorded> = load()

  /**
   * La testa di [device] (ora del NAS [headAtNas]) e' stata letta per intero e contiene [entries] (gia' giudicate valide): se ne ricordano le
   * voci guardate. Vero se e' ricordata in modo durevole (o se non c'e' un file); falso se il file non si e' scritto.
   */
  fun record(device: String, headAtNas: Long, entries: Collection<PersonalEntry>): Boolean = synchronized(lock) {
    val kept = entries.filter { it.key in watched && encodable(it.value) }.associateBy { it.key }
    val before = heads[device]
    if (before != null && before.headAtNas == headAtNas && before.entries == kept) return@synchronized true
    heads[device] = Recorded(headAtNas, kept)
    save()
  }

  override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> {
    if (key !in watched) return emptyMap()
    return synchronized(lock) { heads.mapValues { (_, r) -> HeadValue(r.entries[key], r.headAtNas) } }
  }

  override fun toString(): String = "PersonalHeadsRecord(${synchronized(lock) { heads.size }} dispositivi)"

  // --- file --------------------------------------------------------------------------------------------------------------------

  private fun save(): Boolean {
    val target = file ?: return true
    val text = buildString {
      append(MAGIC).append('\n')
      for ((device, r) in heads.toSortedMap()) {
        append("D\t").append(device).append('\t').append(r.headAtNas).append('\n')
        for (e in r.entries.values) {
          append("E\t").append(e.key.kind.code).append('\t').append(b64(e.key.subject)).append('\t').append(b64(e.key.field)).append('\t')
          append(e.atNas).append('\t').append(e.author).append('\t').append(valueText(e.value)).append('\n')
        }
      }
    }
    return try {
      StateFiles.writeText(target, text, label = LABEL)
      true
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "teste personali non ricordate (${e.javaClass.simpleName})" }
      false
    }
  }

  private fun load(): MutableMap<String, Recorded> {
    val source = file ?: return LinkedHashMap()
    val lines = try {
      StateFiles.readLines(source, label = LABEL)
    } catch (e: IOException) {
      null
    } ?: return LinkedHashMap()
    return try {
      parse(lines)
    } catch (e: IllegalArgumentException) {
      // Rovinato o di un'altra versione: nessuna testa nota (il lato prudente: nessuno ha confermato).
      GalleryLog.w(TAG) { "teste personali illeggibili: si riparte da zero" }
      LinkedHashMap()
    }
  }

  private fun parse(lines: List<String>): MutableMap<String, Recorded> {
    require(lines.firstOrNull() == MAGIC) { "versione" }
    val out = LinkedHashMap<String, Recorded>()
    var device: String? = null
    var at = 0L
    var entries = LinkedHashMap<PersonalKey, PersonalEntry>()
    fun flush() {
      device?.let { out[it] = Recorded(at, entries) }
    }
    for (line in lines.drop(1)) {
      if (line.isEmpty()) continue
      val parts = line.split('\t')
      when (parts[0]) {
        "D" -> {
          require(parts.size == 3) { "riga" }
          flush()
          device = parts[1]
          at = parts[2].toLong()
          entries = LinkedHashMap()
        }
        "E" -> {
          require(parts.size == 7 && device != null) { "voce" }
          val kind = PersonalKind.entries.firstOrNull { it.code == parts[1] } ?: throw IllegalArgumentException("tipo")
          val key = PersonalKey(kind, unb64(parts[2]), unb64(parts[3]))
          val entry = PersonalEntry(key, parts[4].toLong(), parts[5], parseValue(parts[6]))
          if (key in watched) entries[key] = entry
        }
        else -> throw IllegalArgumentException("riga")
      }
    }
    flush()
    return out
  }

  private fun encodable(value: PersonalValue): Boolean =
    value is PersonalValue.Text || value is PersonalValue.Flag || value is PersonalValue.Number || value == PersonalValue.Removed

  private fun valueText(value: PersonalValue): String = when (value) {
    is PersonalValue.Text -> "T:" + b64(value.text)
    is PersonalValue.Flag -> if (value.on) "F:1" else "F:0"
    is PersonalValue.Number -> "N:" + value.value
    PersonalValue.Removed -> "R"
    else -> throw IllegalStateException("valore non ricordabile")
  }

  private fun parseValue(text: String): PersonalValue = when {
    text == "R" -> PersonalValue.Removed
    text == "F:1" -> PersonalValue.Flag(true)
    text == "F:0" -> PersonalValue.Flag(false)
    text.startsWith("N:") -> PersonalValue.Number(text.substring(2).toLong())
    text.startsWith("T:") -> PersonalValue.Text(unb64(text.substring(2)))
    else -> throw IllegalArgumentException("valore")
  }

  private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

  private fun unb64(text: String): String = String(Base64.getDecoder().decode(text), Charsets.UTF_8)

  companion object {
    /** Il nome del file in `noBackupFilesDir` (lo lega `:app`, P13; l'azzeramento lo toglie con gli altri file locali). */
    const val FILE: String = "personal-heads"

    private const val MAGIC = "mcg-personal-heads 1"
    private const val LABEL = "personal-heads"
    private const val TAG = "PersonalHeads"
  }
}
