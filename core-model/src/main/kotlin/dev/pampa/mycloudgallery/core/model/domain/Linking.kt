package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.VersionId

/**
 * Cio' che una scansione (MediaStore o NAS) ha visto in un posto: dove, con quale timbro, con quale
 * impronta. [kind] e [source] servono solo se nasce un media nuovo.
 *
 * [captureTimeHintMillis] e' la data di scatto **non stimata** se e' gia' nota a costo zero (EXIF o
 * `DATE_TAKEN` di MediaStore): un indizio in piu' per riconoscere un file che ha cambiato riga o posto.
 */
data class Observation(
  val location: Location,
  val stamp: FileStamp,
  val fingerprint: Fingerprint,
  val kind: MediaKind,
  val source: MediaSource,
  val captureTimeHintMillis: Long? = null,
) {
  init {
    require(stamp.size == fingerprint.size) { "Il timbro e l'impronta parlano di dimensioni diverse." }
  }
}

/** Una posizione gia' conosciuta, con il suo media intero (servono le altre versioni). */
data class KnownLocation(
  val media: Media,
  val locationId: LocationId,
  /** Data di scatto non stimata della versione in quel posto, se nota. */
  val captureTimeHintMillis: Long? = null,
) {
  init {
    media.location(locationId)
  }

  val location: MediaLocation get() = media.location(locationId)

  val version: MediaVersion get() = media.version(location.versionId)
}

/** Una versione di un media esistente con la stessa dimensione e lo stesso hash rapido dell'osservazione. */
data class ContentCandidate(
  val mediaId: MediaId,
  val version: MediaVersion,
)

/**
 * Tutto cio' che l'indice sa e che serve a decidere, preparato da chi legge il database. Le regole
 * sono pure: non cercano nulla da sole, cosi' si provano con dati scritti a mano.
 */
data class LinkContext(
  /** La posizione con la stessa [Location.key] (stesso posto, a meno di maiuscole: E-N7). */
  val atSameKey: KnownLocation? = null,
  /** Solo telefono: la posizione che in cache ha lo stesso `_ID` MediaStore, in un altro posto. */
  val atSameMediaStoreId: KnownLocation? = null,
  /** Le versioni (di qualunque media) con stessa dimensione e stesso hash rapido. */
  val sameContent: List<ContentCandidate> = emptyList(),
  /** Le posizioni [dev.pampa.mycloudgallery.core.model.LocationState.ABSENT] con stessa dimensione e hash rapido. */
  val vanished: List<KnownLocation> = emptyList(),
)

/** Perche' due osservazioni sono state considerate lo stesso file. */
enum class LinkEvidence {
  /** Stessa chiave di posto e stessi byte (almeno l'hash rapido). */
  SAME_PLACE,

  /** Stesso `_ID` letto con la stessa versione del MediaStore: il file e' stato rinominato o spostato. */
  MEDIASTORE_ROW,

  /** Hash completi uguali: gli stessi byte, ovunque siano. */
  FULL_HASH,

  /** Un file sparito e uno comparso con gli stessi byte probabili e un indizio indipendente in piu'. */
  MOVE_DETECTED,

  /** L'app stessa ha copiato i byte (backup, "Scarica sul telefono", condivisione) e li ha verificati. */
  APP_TRANSFER,
}

/** La decisione su un'osservazione. Chi la applica lo fa in una transazione. */
sealed interface LinkDecision {

  /** Stesso posto, stessi byte. Cambiano al piu' timbro e cache; se il timbro cambia la verifica decade. */
  data class SameFile(
    val locationId: LocationId,
    val mediaId: MediaId,
    /** Era assente (es. ripristinata dal cestino di Galleria, E-P11): torna presente, stesso media. */
    val reappeared: Boolean,
    val stampChanged: Boolean,
  ) : LinkDecision

  /** Stesso posto, con continuita', byte nuovi: una **nuova versione** dello stesso media. */
  data class ContentChanged(
    val locationId: LocationId,
    val mediaId: MediaId,
    val previousVersionId: VersionId,
  ) : LinkDecision

  /** Stesso posto, i byte sono tornati a quelli di una versione gia' nota (es. "Ripristina originale", E-E2). */
  data class RevertedToVersion(
    val locationId: LocationId,
    val mediaId: MediaId,
    val versionId: VersionId,
  ) : LinkDecision

  /** Lo stesso file e' in un altro posto: cambia la posizione, non l'identita' (E-N1). */
  data class Moved(
    val locationId: LocationId,
    val mediaId: MediaId,
    val evidence: LinkEvidence,
  ) : LinkDecision

  /**
   * Gli stessi byte di una versione esistente, in un posto in piu' (E-L1, E-L3). [alsoIdenticalTo]
   * elenca altri media con gli stessi byte: andrebbero uniti a [mediaId] (prova: hash completo).
   */
  data class NewLocation(
    val mediaId: MediaId,
    val versionId: VersionId,
    val evidence: LinkEvidence,
    val alsoIdenticalTo: List<MediaId> = emptyList(),
  ) : LinkDecision

  /**
   * Nel dubbio non si unisce: ci sono candidati con lo stesso hash rapido ma manca l'hash completo
   * [dalla parte osservata][observedHashMissing] o da quella dei candidati. Chi chiama calcola l'hash
   * completo e ripete; se non puo' (originale irraggiungibile) crea un media nuovo, che si unira' piu'
   * tardi quando le impronte complete lo dimostreranno (`MergeRules`).
   */
  data class NeedsFullHash(
    val candidates: List<ContentCandidate>,
    val observedHashMissing: Boolean,
  ) : LinkDecision

  /** Nessun media corrisponde: nasce un media nuovo, con la sua versione originale. */
  data object NewMedia : LinkDecision

  /**
   * Nel posto c'era un altro file e non c'e' prova che sia lo stesso modificato: la vecchia posizione
   * esce dal suo media e per i byte nuovi vale [next].
   */
  data class Replaced(
    val previousLocationId: LocationId,
    val previousMediaId: MediaId,
    val next: LinkDecision,
  ) : LinkDecision {
    init {
      require(next is NewLocation || next is NewMedia || next is NeedsFullHash || next is Moved) {
        "Dopo una sostituzione i byte nuovi si collegano per contenuto."
      }
    }
  }
}
