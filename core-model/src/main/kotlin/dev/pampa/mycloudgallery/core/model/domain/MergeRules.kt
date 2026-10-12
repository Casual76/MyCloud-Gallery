package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.EmbeddingVector
import dev.pampa.mycloudgallery.core.model.FingerprintMatch
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind

/** Perche' due media non si uniscono. */
enum class MergeRefusal {
  SAME_MEDIA,

  /**
   * Gli originali non hanno hash completi uguali. Due media con originali diversi (o non ancora
   * dimostrati uguali) restano separati, anche se condividono una versione: nel dubbio non si unisce.
   */
  ORIGINALS_NOT_PROVEN_IDENTICAL,
}

sealed interface MergePlan {

  data class Refused(val reason: MergeRefusal) : MergePlan

  /**
   * L'unione di [loser] in [survivor]. [versionMapping] traduce ogni versione del perdente che ha gli
   * stessi byte di una del sopravvissuto; le altre ([movedVersions]) passano al sopravvissuto con il
   * genitore tradotto. [merged] e' il media risultante (gia' verificato dai suoi invarianti).
   */
  data class Merge(
    val survivor: MediaId,
    val loser: MediaId,
    val versionMapping: Map<VersionId, VersionId>,
    val movedVersions: List<MediaVersion>,
    val merged: Media,
  ) : MergePlan {
    /** La versione del media unito che corrisponde a [versionId] del perdente o del sopravvissuto. */
    fun translate(versionId: VersionId): VersionId = versionMapping[versionId] ?: versionId
  }
}

/**
 * L'unione di due media che si rivelano lo stesso (E-L3 a posteriori, doppioni di scansioni
 * indipendenti, dalla Fase 4 identita' assegnate da due dispositivi allo stesso file).
 *
 * - **Prova richiesta**: originali con hash completi uguali. Nient'altro.
 * - **Sopravvissuto deterministico**: l'identita' minore. Due dispositivi che uniscono gli stessi media
 *   per conto loro arrivano allo stesso risultato, senza parlarsi.
 * - **Il perdente non sparisce**: diventa un *alias* del sopravvissuto (lo registra chi applica il
 *   piano), cosi' un riferimento esterno con la vecchia identita' (dati personali sincronizzati, indice
 *   condiviso) trova ancora il media.
 * - **Corrente**: la piu' recente tra le due correnti (una modifica non va persa); a parita', quella del
 *   sopravvissuto.
 */
object MergeRules {

  fun survivorOf(a: MediaId, b: MediaId): MediaId = minOf(a, b)

  /**
   * [localAccount] e' l'account del dispositivo: se uno dei due media era suo, il media unito resta
   * suo (si sbaglia dal lato della sicurezza: un obbligo di copia privata in piu', mai uno in meno).
   */
  fun plan(a: Media, b: Media, localAccount: AccountId?): MergePlan {
    if (a.id == b.id) return MergePlan.Refused(MergeRefusal.SAME_MEDIA)
    val survivor = if (survivorOf(a.id, b.id) == a.id) a else b
    val loser = if (survivor === a) b else a
    if (survivor.originalVersion.fingerprint.match(loser.originalVersion.fingerprint) != FingerprintMatch.SAME) {
      return MergePlan.Refused(MergeRefusal.ORIGINALS_NOT_PROVEN_IDENTICAL)
    }

    val mapping = buildMap {
      put(loser.originalVersion.id, survivor.originalVersion.id)
      loser.versions.filter { it.kind != VersionKind.ORIGINAL }.forEach { theirs ->
        survivor.versions.firstOrNull { it.fingerprint.match(theirs.fingerprint) == FingerprintMatch.SAME }
          ?.let { put(theirs.id, it.id) }
      }
    }
    val moved = loser.versions
      .filter { it.id !in mapping }
      .map { v -> v.copy(parentId = v.parentId?.let { mapping[it] ?: it }) }
    val versions = survivor.versions + moved

    val candidates = listOf(survivor.currentVersionId, mapping[loser.currentVersionId] ?: loser.currentVersionId)
      .map { id -> versions.first { it.id == id } }
    val current = candidates.maxWith(
      compareBy<MediaVersion> { it.createdAtMillis }.thenBy { if (it.id == survivor.currentVersionId) 1 else 0 },
    )

    val owner = when {
      localAccount != null && (survivor.owner == localAccount || loser.owner == localAccount) -> localAccount
      else -> survivor.owner ?: loser.owner
    }
    val merged = survivor.copy(
      source = if (survivor.source.isSpecific || !loser.source.isSpecific) survivor.source else loser.source,
      owner = owner,
      createdAtMillis = minOf(survivor.createdAtMillis, loser.createdAtMillis),
      currentVersionId = current.id,
      versions = versions,
      locations = survivor.locations + loser.locations.map { it.copy(versionId = mapping[it.versionId] ?: it.versionId) },
      groupId = survivor.groupId ?: loser.groupId,
    )
    return MergePlan.Merge(survivor.id, loser.id, mapping, moved, merged)
  }

  /**
   * Quale vettore tenere quando entrambi i media ne hanno uno nello stesso spazio e indice: quello
   * calcolato dalla versione corrente del media unito, altrimenti quello del sopravvissuto.
   */
  fun chooseVector(survivor: EmbeddingVector, loser: EmbeddingVector, currentVersionId: VersionId): EmbeddingVector {
    require(survivor.space == loser.space && survivor.index == loser.index) { "Vettori di spazi o indici diversi." }
    return if (loser.computedFrom == currentVersionId && survivor.computedFrom != currentVersionId) loser else survivor
  }
}
