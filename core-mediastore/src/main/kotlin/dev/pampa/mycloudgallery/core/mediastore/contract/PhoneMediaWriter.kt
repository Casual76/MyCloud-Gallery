package dev.pampa.mycloudgallery.core.mediastore.contract

import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.Sha256

// "SCARICA SUL TELEFONO" (Fase 4, docs/fase4-progetto.md §2.6, D20, E-L2). Contratto di P0, implementazione di P8
// (`write/`). La usano il viewer (P8, menu a capacita') e :app (P11, selezione). Nessun permesso nuovo: i file li crea
// l'app (MediaStore con IS_PENDING).

/** Cosa salvare: nome proposto (i conflitti diventano "nome (2)"), tipo e dimensione attesa. */
data class SaveRequest(val displayName: String, val mime: String, val size: Long) {
  init {
    require(displayName.isNotBlank() && '/' !in displayName && displayName.length <= 255) { "Nome non valido." }
    require(size >= 0) { "Dimensione negativa." }
  }

  /** Il nome e' un dato dell'utente: mai nei log. */
  override fun toString(): String = "SaveRequest($mime, $size B)"
}

/**
 * Da dove arrivano i byte (in :app: `NasMediaReader.readAll` sull'originale del NAS). [copyTo] consegna i blocchi in
 * ordine al `sink` (buffer e lunghezza valida; il buffer si riusa dopo il ritorno) e lancia `IOException` se la
 * sorgente si interrompe: il temporaneo si toglie e nessun file a meta' resta visibile.
 */
fun interface PhoneSaveSource {
  suspend fun copyTo(sink: (ByteArray, Int) -> Unit)
}

/** La copia scritta, ancora **in attesa** (`IS_PENDING = 1`): non la vede nessuno, nemmeno la lettura del telefono. */
data class PendingCopy(val location: Location.Phone, val sha256: Sha256, val size: Long) {
  override fun toString(): String = "PendingCopy($size B)"
}

/** L'esito di un salvataggio. */
sealed interface SaveResult {
  data class Saved(val copy: PendingCopy) : SaveResult

  /** Annullato dall'utente o dal ciclo di vita: il temporaneo e' stato tolto. */
  data object Cancelled : SaveResult

  /** [code] stabile: `SOURCE_FAILED`, `HASH_MISMATCH`, `PHONE_FULL`, `MEDIASTORE_ERROR`... mai un messaggio. */
  data class Failed(val code: String) : SaveResult
}

/**
 * Salva un media in `Pictures/MyCloud Gallery` (D20): riga MediaStore con `IS_PENDING = 1`, copia con SHA-256 calcolato
 * mentre arriva, poi [beforePublish] con la copia **ancora in attesa** — qui :app collega la posizione al catalogo
 * (`attachCopy` con l'impronta) — e solo dopo `IS_PENDING = 0`. Cosi' la lettura del telefono trova `SameFile` e non
 * nasce un doppione (E-L2). Morte del processo a meta': la riga in attesa si ripulisce al giro dopo (P8).
 *
 * [expectedSha256], se noto (dall'indice o da una verifica), si confronta alla fine: diverso = `HASH_MISMATCH`, nessun
 * file visibile.
 */
interface PhoneMediaWriter {
  suspend fun saveToPictures(
    request: SaveRequest,
    source: PhoneSaveSource,
    expectedSha256: Sha256? = null,
    progress: (Long) -> Unit = {},
    beforePublish: suspend (PendingCopy) -> Unit = {},
  ): SaveResult
}
