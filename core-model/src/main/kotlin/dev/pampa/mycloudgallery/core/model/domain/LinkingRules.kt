package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.FingerprintMatch
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState

/**
 * Le regole che collegano un file visto a un media (piano 1.3, 4.4; E-L1…E-L3, E-N1, E-N2, E-N7,
 * E-P11, E-P12, E-E2). Sono pure: ricevono cio' che l'indice sa ([LinkContext]) e rispondono con una
 * [LinkDecision].
 *
 * **Principio: nel dubbio NON unire.** Unire per errore due media diversi e' il danno peggiore: uno
 * erediterebbe album, preferiti e soprattutto lo stato "nascosto" dell'altro, e si perderebbe traccia
 * di un file. Tenere separati due media uguali costa al piu' un doppione temporaneo, che si unisce
 * quando le impronte complete lo dimostrano. Quindi:
 *
 * 1. **Stesso posto** (stessa [Location.key]): stessi byte -> stesso file. Byte diversi -> nuova
 *    versione **solo con continuita'** (vedi [hasContinuity]); senza, il file e' stato sostituito.
 *    Dentro lo stesso media l'hash rapido basta a riconoscere il ritorno a una versione nota.
 * 2. **Stessa riga MediaStore** (`_ID` in cache **e** stessa `getVersion()`) e stessi byte probabili:
 *    il file e' stato rinominato o spostato sul telefono. Un `_ID` letto con un'altra versione del
 *    MediaStore non prova nulla: gli ID ripartono e possono essere di un altro file (`docs/android.md`).
 * 3. **Spostamento**: una posizione sparita dello stesso tipo, con stessi byte probabili **e** un
 *    indizio indipendente (hash completo uguale, stessa data di modifica, stessa data di scatto e, sul NAS,
 *    lo stesso `FileId` nella stessa share).
 *    Se piu' media diversi sono candidati, decide solo l'hash completo.
 * 4. **Stessi byte altrove**: si collega **solo con hash completi uguali**. Con il solo hash rapido
 *    uguale la risposta e' [LinkDecision.NeedsFullHash], mai un'unione.
 * 5. Altrimenti, media nuovo.
 */
object LinkingRules {

  fun decide(observation: Observation, context: LinkContext): LinkDecision {
    context.atSameKey?.let { known ->
      require(known.location.location.key == observation.location.key) { "atSameKey non e' nello stesso posto." }
      return decideAtSamePlace(observation, known, context)
    }
    context.atSameMediaStoreId?.let { known ->
      decideBySameMediaStoreRow(observation, known)?.let { return it }
    }
    return decideByContent(observation, context)
  }

  private fun decideAtSamePlace(observation: Observation, known: KnownLocation, context: LinkContext): LinkDecision {
    val before = known.location
    return when (known.version.fingerprint.match(observation.fingerprint)) {
      FingerprintMatch.SAME, FingerprintMatch.PROBABLY_SAME -> LinkDecision.SameFile(
        locationId = before.id,
        mediaId = known.media.id,
        reappeared = !before.isPresent,
        stampChanged = !observation.stamp.looksUnchangedFrom(before.stamp),
      )
      FingerprintMatch.DIFFERENT ->
        if (hasContinuity(known, observation)) {
          changedInPlace(observation, known)
        } else {
          LinkDecision.Replaced(before.id, known.media.id, decideByContent(observation, context.copy(atSameKey = null)))
        }
    }
  }

  /**
   * Se il file nel posto e' "lo stesso file, modificato" o un file diverso che ne ha preso il nome.
   *
   * - NAS: il percorso e' l'identita' della posizione, e un file presente che cambia e' una modifica
   *   (E-N2, E-E6). Non si usa la data di scatto per escluderlo: una correzione dal PC la cambia.
   * - Telefono: serve la stessa riga MediaStore (stesso `_ID` con la stessa versione). Se la riga e'
   *   cambiata (Samsung puo' riscrivere il file come riga nuova), basta la stessa data di scatto non
   *   stimata: un editor la conserva, una foto diversa con lo stesso nome quasi mai.
   * - Una posizione assente o non raggiungibile non ha continuita': nel frattempo puo' essere successo
   *   di tutto.
   */
  private fun hasContinuity(known: KnownLocation, observation: Observation): Boolean {
    val before = known.location
    if (!before.isPresent) return false
    return when (val now = observation.location) {
      is Location.Nas -> true
      is Location.Phone -> {
        val beforePhone = before.location as Location.Phone
        beforePhone.hasSameMediaStoreRowAs(now) ||
          sameCaptureTime(known.captureTimeHintMillis, observation.captureTimeHintMillis)
      }
    }
  }

  private fun changedInPlace(observation: Observation, known: KnownLocation): LinkDecision {
    val before = known.location
    val compatible = known.media.versions.filter {
      it.id != before.versionId && it.fingerprint.match(observation.fingerprint) != FingerprintMatch.DIFFERENT
    }
    val target = compatible.firstOrNull { it.fingerprint.match(observation.fingerprint) == FingerprintMatch.SAME }
      ?: compatible.singleOrNull()
    return if (target != null) {
      LinkDecision.RevertedToVersion(before.id, known.media.id, target.id)
    } else {
      LinkDecision.ContentChanged(before.id, known.media.id, before.versionId)
    }
  }

  private fun decideBySameMediaStoreRow(observation: Observation, known: KnownLocation): LinkDecision? {
    val now = observation.location as? Location.Phone ?: return null
    val before = known.location.location as? Location.Phone ?: return null
    if (!before.hasSameMediaStoreRowAs(now)) return null
    if (known.version.fingerprint.match(observation.fingerprint) == FingerprintMatch.DIFFERENT) return null
    return LinkDecision.Moved(known.location.id, known.media.id, LinkEvidence.MEDIASTORE_ROW)
  }

  private fun decideByContent(observation: Observation, context: LinkContext): LinkDecision {
    detectMove(observation, context.vanished)?.let { return it }

    val compatible = context.sameContent.filter {
      it.version.fingerprint.match(observation.fingerprint) != FingerprintMatch.DIFFERENT
    }
    val identical = compatible.filter { it.version.fingerprint.match(observation.fingerprint) == FingerprintMatch.SAME }
    if (identical.isNotEmpty()) {
      val mediaIds = identical.map { it.mediaId }.distinct().sorted()
      val target = identical.first { it.mediaId == mediaIds.first() }
      return LinkDecision.NewLocation(target.mediaId, target.version.id, LinkEvidence.FULL_HASH, mediaIds.drop(1))
    }
    if (compatible.isNotEmpty()) {
      return LinkDecision.NeedsFullHash(compatible, observedHashMissing = observation.fingerprint.fullHash == null)
    }
    return LinkDecision.NewMedia
  }

  private fun detectMove(observation: Observation, vanished: List<KnownLocation>): LinkDecision? {
    val candidates = vanished.filter {
      it.location.state == LocationState.ABSENT &&
        it.location.location.type == observation.location.type &&
        it.location.location.key != observation.location.key &&
        it.version.fingerprint.match(observation.fingerprint) != FingerprintMatch.DIFFERENT
    }
    val corroborated = candidates.filter { isCorroboratedMove(it, observation) }
    if (corroborated.isEmpty()) return null

    val media = corroborated.map { it.media.id }.distinct()
    if (media.size == 1) {
      val chosen = corroborated.minBy { it.location.id.value }
      return LinkDecision.Moved(chosen.location.id, chosen.media.id, LinkEvidence.MOVE_DETECTED)
    }
    val strong = corroborated.filter { it.version.fingerprint.match(observation.fingerprint) == FingerprintMatch.SAME }
    if (strong.isNotEmpty()) {
      val chosen = strong.minWith(compareBy({ it.media.id }, { it.location.id.value }))
      return LinkDecision.Moved(chosen.location.id, chosen.media.id, LinkEvidence.FULL_HASH)
    }
    return LinkDecision.NeedsFullHash(
      corroborated.map { ContentCandidate(it.media.id, it.version) },
      observedHashMissing = observation.fingerprint.fullHash == null,
    )
  }

  private fun isCorroboratedMove(candidate: KnownLocation, observation: Observation): Boolean =
    candidate.version.fingerprint.match(observation.fingerprint) == FingerprintMatch.SAME ||
      candidate.location.stamp.modifiedAtMillis == observation.stamp.modifiedAtMillis ||
      sameCaptureTime(candidate.captureTimeHintMillis, observation.captureTimeHintMillis) ||
      sameNasFileId(candidate.location.location, observation.location)

  private fun sameCaptureTime(a: Long?, b: Long?): Boolean = a != null && b != null && a == b

  /**
   * Fase 4 (E-N1, §2.3): lo stesso `FileId` SMB, noto da entrambe le parti, nella stessa share dello stesso account. Il
   * `FileId` resta uguale a una rinomina o a uno spostamento sullo stesso volume (anche quando il PC "tocca" la data di
   * modifica); in un'altra share e' un altro volume e non prova nulla.
   */
  private fun sameNasFileId(before: Location, now: Location): Boolean {
    if (before !is Location.Nas || now !is Location.Nas) return false
    val id = before.fileId ?: return false
    return id == now.fileId && before.account == now.account && before.shareKey == now.shareKey
  }
}
