package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FingerprintMatch
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId

/**
 * L'identita' che un altro dispositivo ha pubblicato per un file del NAS (record `meta` dell'indice condiviso: `media`,
 * `version`, `size`, `qh`, `fh`), letta e verificata da P4. **Dato remoto**: vale per l'identita' dei media solo-NAS (D8),
 * mai per unire byte senza prova.
 */
data class RemoteIdentity(
  val mediaId: MediaId,
  val versionId: VersionId,
  val fingerprint: Fingerprint,
)

/** Perche' un'adozione d'identita' e' stata rifiutata. [isConflict]: e' un conflitto di D7 da contare in Diagnostica. */
enum class AdoptRefusal(val isConflict: Boolean) {
  /**
   * Il record non descrive i byte che il catalogo ha in quel posto (dimensione o `qh1` diversi): un record vecchio o di
   * un altro file. Non e' un conflitto d'identita': la fusione D6 (timbro) l'avrebbe gia' scartato.
   */
  CONTENT_MISMATCH(false),

  /** D7: l'id remoto (media o versione) e' gia' legato qui a un contenuto diverso (dimensione o `qh1`). */
  ID_BOUND_TO_OTHER_CONTENT(true),

  /** D7: la versione remota e' gia' qui, ma dentro un altro media rispetto a quello che il record dichiara. */
  ID_BOUND_ELSEWHERE(true),

  /**
   * L'id remoto e' gia' qui un altro media con byte **probabilmente** uguali, ma senza hash completi uguali degli
   * originali: nel dubbio non si unisce (si riprovera' quando l'hash completo ci sara'). Non e' un conflitto.
   */
  NOT_PROVEN_IDENTICAL(false),
}

/** La decisione su un'adozione. Chi la applica (il catalogo) lo fa in una transazione. */
sealed interface AdoptionDecision {

  /** Le identita' coincidono gia' (o la versione remota e' gia' una di quelle del media). */
  data object AlreadyShared : AdoptionDecision

  /**
   * Si adottano gli id remoti **minori**: [mediaTo] per il media, [versionTo] per la versione nel posto (`null` = quell'id
   * resta il nostro, perche' il nostro e' il minore). L'id vecchio resta come alias: i riferimenti esterni lo ritrovano.
   */
  data class Rename(val mediaTo: MediaId?, val versionTo: VersionId?) : AdoptionDecision {
    init {
      require(mediaTo != null || versionTo != null) { "Una rinomina cambia almeno un id." }
    }
  }

  /**
   * Si tengono gli id locali: sono i minori (l'altro dispositivo convergera' leggendo il nostro record) oppure il media ha
   * copie sul telefono, e i dati dell'indice valgono per l'identita' dei soli media solo-NAS (D8).
   */
  data object KeepLocal : AdoptionDecision

  /**
   * L'id remoto e' qui un altro media ([other]) con byte compatibili: i due id sono lo stesso file per l'altro dispositivo.
   * Si prova l'unione con le regole di sempre (`MergeRules`: originali con hash completi uguali, sopravvive il minore).
   */
  data class MergeWith(val other: MediaId) : AdoptionDecision

  data class Refuse(val reason: AdoptRefusal) : AdoptionDecision
}

/**
 * L'adozione delle identita' condivise (D7, E-I18; `docs/modello-dati.md` §9 e §11).
 *
 * - Chi registra per primo un file del NAS pubblica `MediaId` e `VersionId`; gli altri li adottano. Due id per lo stesso
 *   file **convergono al minore**, come in `MergeRules`: ogni dispositivo arriva allo stesso risultato senza parlarsi
 *   (chi ha il minore lo tiene, chi ha il maggiore lo cambia quando legge l'altro).
 * - **Rifiuto**: l'adozione e' rifiutata se l'id remoto e' gia' legato, qui, a un contenuto diverso (dimensione o `qh1`).
 *   Senza questa regola un dispositivo compromesso potrebbe pubblicare l'id di una mia foto per un suo file e farla
 *   "sparire" in un'unione.
 * - **Solo media solo-NAS** (D8): un media con copie sul telefono tiene il suo id; l'unione con un file del NAS passa
 *   sempre dalle impronte lette qui.
 */
object IdentityRules {

  /**
   * [local]: il media che ha la posizione [locationId] (il file del record). [remoteMediaHere]: il media che l'id remoto
   * indica qui (direttamente o tramite un alias), se c'e' (puo' essere [local]). [remoteVersionOwner]: il media che ha qui
   * una versione con l'id della versione remota, se c'e'.
   */
  fun decide(
    local: Media,
    locationId: LocationId,
    remote: RemoteIdentity,
    remoteMediaHere: Media?,
    remoteVersionOwner: Media?,
  ): AdoptionDecision {
    val atLocation = local.version(local.location(locationId).versionId)
    if (atLocation.fingerprint.match(remote.fingerprint) == FingerprintMatch.DIFFERENT) {
      return AdoptionDecision.Refuse(AdoptRefusal.CONTENT_MISMATCH)
    }
    remoteVersionOwner?.let { owner ->
      if (owner.version(remote.versionId).fingerprint.match(remote.fingerprint) == FingerprintMatch.DIFFERENT) {
        return AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT)
      }
    }

    if (remoteMediaHere != null && remoteMediaHere.id != local.id) {
      val compatible = remoteMediaHere.versions.any { it.fingerprint.match(remote.fingerprint) != FingerprintMatch.DIFFERENT }
      if (!compatible) return AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT)
      if (remoteVersionOwner != null && remoteVersionOwner.id != remoteMediaHere.id) {
        return AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_ELSEWHERE)
      }
      return AdoptionDecision.MergeWith(remoteMediaHere.id)
    }

    // Il media remoto e' questo, oppure qui non esiste ancora.
    if (remoteVersionOwner != null && remoteVersionOwner.id != local.id) {
      return AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_ELSEWHERE)
    }
    val sameMedia = remoteMediaHere != null
    val versionKnown = remoteVersionOwner != null
    if (sameMedia && (versionKnown || remote.versionId == atLocation.id)) return AdoptionDecision.AlreadyShared
    if (local.locations.any { it.isPhone }) return AdoptionDecision.KeepLocal

    val mediaTo = remote.mediaId.takeIf { !sameMedia && it < local.id }
    val versionTo = remote.versionId.takeIf { !versionKnown && it.value < atLocation.id.value }
    return if (mediaTo == null && versionTo == null) AdoptionDecision.KeepLocal else AdoptionDecision.Rename(mediaTo, versionTo)
  }
}
