package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 5B (docs/fase5-progetto.md §1.2 "Sessioni (5B)", §1.5 `EditSessionState`, §2.7 "Modifica con…", D25, D31, E-E3, E-E7, E-X3, E-X1;
// riga P0-B del §4.4): le SESSIONI DI MODIFICA, il modo in cui l'app capisce che cosa ha fatto un editor esterno. Samsung Gallery non risponde
// ad `ACTION_EDIT` (scoperta 11): si apre con `ACTION_VIEW` e l'esito si legge dal MediaStore. Contratto, null-object e fake di P0-B; la logica
// (confronto della riga e del timbro, la riga nuova nella stessa cartella con la stessa data di scatto, i byte della copia in cache) e' di P11.
//
// **Gli istanti sono ora del NAS** (D31, F4-D32): una sessione sopravvive alla morte del processo mentre l'utente modifica in un'altra app, quindi
// si salva con un istante assoluto ([EditSession.startedAtNasMillis], [EditSession.expiresAtNasMillis]) e non con una durata. La modifica non
// parte mai da un dato letto dal NAS: la sessione nasce da un tocco su "Modifica con…" (il chiamante e' la UI).

/** Con quale via si modifica un media (la colonna `target` di `edit_session`). */
enum class EditTarget(override val code: String) : StableCode {
  /** Un media del telefono: `ACTION_VIEW` verso Samsung Gallery (pacchetto esplicito); l'esito si legge dal MediaStore. */
  PHONE_GALLERY("phone_gallery"),

  /** Un media solo sul NAS: copia in `cacheDir/edit/` servita da `FileProvider` in lettura e scrittura, `ACTION_EDIT` (Google Foto, OneDrive). */
  NAS_CACHE("nas_cache"),

  /** Un media solo sul NAS: "Scarica e modifica in Galleria" (copia in `Pictures/MyCloud Gallery`, F4-D20; poi come [PHONE_GALLERY], E-E7). */
  NAS_DOWNLOAD("nas_download"),
}

/** A che punto e' una sessione. */
enum class EditSessionState(override val code: String, val terminal: Boolean = false) : StableCode {
  /** Aperta: l'utente sta modificando altrove; i cambiamenti del MediaStore o della copia in cache sono suoi. */
  OPEN("open"),

  /** Chiusa con un esito ([EditOutcome]). */
  SETTLED("settled", terminal = true),

  /** Scaduta (30 minuti): un cambiamento dopo non e' piu' attribuito alla sessione (sarebbe una versione normale, E-E5). */
  EXPIRED("expired", terminal = true),

  /** Annullata dall'utente. */
  CANCELLED("cancelled", terminal = true),
}

/**
 * Una sessione di "Modifica con…". [cacheName] e' il nome opaco del file in `cacheDir/edit/` (mai un percorso): lo serve il `FileProvider` e lo
 * scrive `begin` solo per [EditTarget.NAS_CACHE]. [phone] e' la riga del telefono da aprire in Samsung Gallery ([EditTarget.PHONE_GALLERY]).
 * Nessun nome di file ne' percorso nel `toString`.
 */
data class EditSession(
  val id: EditSessionId,
  val mediaId: MediaId,
  val versionId: VersionId,
  val target: EditTarget,
  val state: EditSessionState,
  val phone: PhoneRef?,
  val cacheName: String?,
  val startedAtNasMillis: Long,
  val expiresAtNasMillis: Long,
) {
  init {
    require(expiresAtNasMillis >= startedAtNasMillis) { "La sessione scade dopo l'inizio." }
  }

  override fun toString(): String = "EditSession(#$id, ${target.code}, ${state.code})"
}

/** L'id di una sessione di modifica (riga di `edit_session`, positivo). */
@JvmInline
value class EditSessionId(val value: Long) {
  init {
    require(value > 0) { "Id di sessione non valido." }
  }

  override fun toString(): String = value.toString()
}

/** Perche' una sessione non si apre. */
enum class EditUnavailable(override val code: String) : StableCode {
  /** Un media non di questo account. */
  NOT_MINE("not_mine"),

  /** Il media del telefono non ha piu' una riga (cancellata, E-B4). */
  GONE_FROM_PHONE("gone_from_phone"),

  /** Un media solo sul NAS con il NAS irraggiungibile: errore subito con il motivo, mai un'attesa infinita (E-X1). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Telefono senza spazio per la copia in cache o in Galleria. */
  PHONE_FULL("phone_full"),

  /** Nessuna implementazione legata (null-object). */
  NOT_AVAILABLE("not_available"),
}

/** L'esito di [EditSessions.begin]. */
sealed interface EditBegin {
  data class Started(val session: EditSession) : EditBegin

  data class Unavailable(val reason: EditUnavailable) : EditBegin
}

/** Che cosa ha fatto l'editor, letto da [EditSessions.settle]. */
sealed interface EditOutcome {
  /** Nessun cambiamento: niente da fare. */
  data object Unchanged : EditOutcome

  /** "Salva" o la copia in cache riscritta: la stessa riga o gli stessi byte sono cambiati; [version] e' la versione nuova dello stesso media (D18, E-E3). */
  data class NewVersion(val version: VersionId) : EditOutcome

  /** "Salva copia" dentro la sessione: una riga nuova nella stessa cartella con la stessa data di scatto e' una versione dello stesso media (E-X3). */
  data class NewVersionFromCopy(val version: VersionId) : EditOutcome

  /** L'editor ha salvato un file altrove che non si riconosce come versione: e' un media nuovo (ricollegato per impronta se il catalogo lo conosce). */
  data object NewMedia : EditOutcome

  /** "Ripristina originale" in Samsung Gallery (E-E2): la riga e' tornata ai byte dell'originale, nessun caricamento. */
  data object RestoredOriginal : EditOutcome

  /** La sessione e' scaduta (30 minuti) o annullata. */
  data object Expired : EditOutcome

  /** Non si e' potuto leggere l'esito (permessi ritirati, riga sparita): la sessione resta aperta, riprova. */
  data object Unreadable : EditOutcome
}

object EditSessionRules {
  /** La finestra di una sessione: 30 minuti (D25). */
  const val WINDOW_MS: Long = 30L * 60 * 1000
}

/** Le sessioni di "Modifica con…" (D25). */
interface EditSessions {

  /**
   * Apre una sessione per [mediaId] con [target]: ricorda la riga e il timbro di partenza (telefono), o prepara la copia in cache (NAS), e
   * dura [EditSessionRules.WINDOW_MS]. Non lancia l'editor: lo fa la UI con l'intento giusto.
   */
  suspend fun begin(mediaId: MediaId, target: EditTarget): EditBegin

  /**
   * Legge che cosa e' cambiato (alla ripresa dell'app, o a richiesta) e chiude la sessione se c'e' un esito. Una modifica fuori dalla sessione non
   * e' un esito di sessione (E-X3): e' una versione normale (E-E1), che la lettura del telefono riconosce da sola.
   */
  suspend fun settle(session: EditSession): EditOutcome
}
