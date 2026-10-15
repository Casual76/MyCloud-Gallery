package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue

// Dallo stato unito allo stato dell'app: i preferiti e i nascosti vivono nel catalogo (`media_personal`), che e' cio' che la Libreria
// legge (E-H1: un nascosto non compare in Libreria, filtri, Raccolte, ricerche). La proiezione e' a senso unico: lo stato unito
// decide, il catalogo lo segue. Gli album, i ricordi e le impostazioni non hanno (ancora) una tabella che li segua: vivono nello
// stato unito e li legge chi li usa (`PersonalAlbums`, `SyncedPersonalSettings`).

/** Come e' andata l'applicazione di una voce. */
enum class ProjectionResult {
  /** Il catalogo ha ora il valore della voce. */
  APPLIED,

  /** Il media non e' (ancora) nel catalogo di questo dispositivo: la voce resta nello stato e si riprova. */
  UNRESOLVED,

  /** Questa voce non ha una proiezione (album, ricordi, impostazioni, campi sconosciuti): niente da fare. */
  NOT_PROJECTED,
}

/** Il valore di un flag nel catalogo locale: una riga di `media_personal` ha sempre entrambi. */
data class LocalFlag(val mediaId: String, val field: String, val on: Boolean)

interface PersonalProjection {
  /** Porta nel catalogo il valore di [entry]. Idempotente. Mai un'eccezione per un media sconosciuto. */
  suspend fun apply(entry: PersonalEntry): ProjectionResult

  /** Dimensione e `qh1` del media a cui si riferisce [key] (per ritrovarlo su un altro dispositivo, §2.9), o null. */
  suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint?

  /**
   * I preferiti e i nascosti di ogni media che ha una riga nel catalogo (acceso o spento), qualunque ne sia la storia: lo store
   * adotta come proprie le differenze dallo stato unito che non sono ancora una voce (preferiti fatti prima che la
   * sincronizzazione esistesse, o dalla Libreria senza passare dallo store).
   */
  suspend fun localFlags(): List<LocalFlag>
}

/** Nessuna proiezione (i test dello stato, i dispositivi senza catalogo). */
object NoProjection : PersonalProjection {
  override suspend fun apply(entry: PersonalEntry): ProjectionResult = ProjectionResult.NOT_PROJECTED

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

  override suspend fun localFlags(): List<LocalFlag> = emptyList()
}

/**
 * La proiezione sul catalogo vero (`MediaCatalog.setPersonalFlags`: stessa transazione e stesso "cambiamento strutturale" di
 * quando l'utente nasconde o preferisce dalla Libreria, quindi l'indice della Libreria e i derivati si aggiornano).
 *
 * L'identita' del media: l'id del catalogo (un'identita' assorbita da un'unione vale per il sopravvissuto, `MediaCatalog.resolve`);
 * se non lo conosce, l'impronta della voce (dimensione e `qh1`): si usa **solo se indica un media solo**. Due media con la stessa
 * impronta rapida (L32: differiscono solo nel mezzo) non si confondono mai, nemmeno per un "nascosto".
 */
class CatalogPersonalProjection(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
) : PersonalProjection {

  override suspend fun apply(entry: PersonalEntry): ProjectionResult {
    val key = entry.key
    if (key.kind != PersonalKind.MEDIA_FLAG) return ProjectionResult.NOT_PROJECTED
    val favorite = when (key.field) {
      PersonalFields.FAVORITE -> true
      PersonalFields.HIDDEN -> false
      else -> return ProjectionResult.NOT_PROJECTED
    }
    // Un soggetto che non e' un'identita' di media (testo ostile) non ha niente da proiettare; uno valido ma sconosciuto si riprova.
    val id = resolve(key.subject, entry.fingerprint)
      ?: return if (mediaIdOrNull(key.subject) == null) ProjectionResult.NOT_PROJECTED else ProjectionResult.UNRESOLVED
    // Una lapide su un flag vuol dire "spento".
    val on = (entry.value as? PersonalValue.Flag)?.on ?: false
    catalog.setPersonalFlags(listOf(id)) { flags -> if (favorite) flags.copy(favorite = on) else flags.copy(hidden = on) }
    return ProjectionResult.APPLIED
  }

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? {
    if (key.kind != PersonalKind.MEDIA_FLAG) return null
    val id = mediaIdOrNull(key.subject) ?: return null
    val media = catalog.media(id) ?: return null
    val fingerprint = media.currentVersion.fingerprint
    return PersonalFingerprint(fingerprint.size, fingerprint.quickHash)
  }

  override suspend fun localFlags(): List<LocalFlag> {
    val out = ArrayList<LocalFlag>()
    database.openHelper.readableDatabase
      .query("SELECT media_id, favorite, hidden FROM media_personal")
      .use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0)
          out += LocalFlag(id, PersonalFields.FAVORITE, cursor.getInt(1) != 0)
          out += LocalFlag(id, PersonalFields.HIDDEN, cursor.getInt(2) != 0)
        }
      }
    return out
  }

  private fun mediaIdOrNull(subject: String): MediaId? = try {
    MediaId.parse(subject)
  } catch (e: IllegalArgumentException) {
    null
  }

  private suspend fun resolve(subject: String, fingerprint: PersonalFingerprint?): MediaId? {
    mediaIdOrNull(subject)?.let { id -> catalog.resolve(id)?.let { return it } }
    if (fingerprint == null) return null
    val candidates = database.versionDao().byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .mapNotNull { catalog.resolve(MediaId(it.mediaId)) }
      .distinct()
    return candidates.singleOrNull()
  }
}
