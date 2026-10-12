package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkStage

/**
 * L'effetto di un cambio di versione: il media dopo il cambio, la versione nata (se e' nata), le
 * posizioni toccate e le fasi di lavoro da rifare.
 */
data class VersionPlan(
  val media: Media,
  val newVersion: MediaVersion?,
  val previousCurrentId: VersionId,
  val changedLocations: List<MediaLocation>,
  val stagesToRedo: Set<WorkStage>,
)

/**
 * Le regole delle versioni (piano 5.4; E-E1, E-E2, E-E6, E-E7, E-L8).
 *
 * Una modifica o una correzione **non** creano un media nuovo: aggiungono una versione alla catena,
 * spostano su di essa solo le posizioni che ora contengono i byte nuovi e la rendono corrente.
 * L'identita' resta, quindi album, preferiti, nascosti e vettori restano attaccati.
 */
object VersionRules {

  /**
   * Il file in [locationId] e' cambiato con continuita' (Samsung Gallery che salva sopra, PC, la copia
   * scaricata in `Pictures/MyCloud Gallery` modificata: E-E7). Nasce una versione [VersionKind.EDITED]
   * figlia di quella che c'era nel posto, e diventa la corrente ("in app si vede la nuova versione").
   */
  fun contentChangedAt(
    media: Media,
    locationId: LocationId,
    fingerprint: Fingerprint,
    stamp: FileStamp,
    nowMillis: Long,
    newVersionId: VersionId = VersionId.random(),
  ): VersionPlan {
    val before = media.location(locationId)
    val version = MediaVersion(
      id = newVersionId,
      kind = VersionKind.EDITED,
      parentId = before.versionId,
      fingerprint = fingerprint,
      createdAtMillis = stamp.modifiedAtMillis,
    )
    val moved = holding(before, version.id, stamp, nowMillis)
    return plan(media, version, listOf(moved), version.id)
  }

  /** Il file in [locationId] e' tornato ai byte di [versionId], gia' nota (E-E2): nessuna versione nuova. */
  fun revertedAt(
    media: Media,
    locationId: LocationId,
    versionId: VersionId,
    stamp: FileStamp,
    nowMillis: Long,
  ): VersionPlan {
    media.version(versionId)
    val moved = holding(media.location(locationId), versionId, stamp, nowMillis)
    return plan(media, null, listOf(moved), versionId)
  }

  /**
   * Una correzione fatta dall'app (data, luogo, rotazione) scritta nel file come nuova versione (E-L8):
   * figlia della versione corrente. [holders] sono i posti che ora contengono i byte corretti, con il
   * loro nuovo timbro; gli altri restano sulla versione di prima (l'originale resta conservato).
   */
  fun corrected(
    media: Media,
    fields: Set<CorrectionField>,
    fingerprint: Fingerprint,
    holders: Map<LocationId, FileStamp>,
    nowMillis: Long,
    newVersionId: VersionId = VersionId.random(),
  ): VersionPlan {
    require(fields.isNotEmpty()) { "Una correzione corregge qualcosa." }
    val version = MediaVersion(
      id = newVersionId,
      kind = VersionKind.CORRECTED,
      parentId = media.currentVersionId,
      fingerprint = fingerprint,
      createdAtMillis = nowMillis,
      corrections = fields,
    )
    val moved = holders.map { (id, stamp) -> holding(media.location(id), version.id, stamp, nowMillis) }
    return plan(media, version, moved, version.id)
  }

  /**
   * "Ripristina originale" dal menu (5.4): l'originale torna la versione corrente. Nessun posto cambia
   * qui: quando i file verranno riscritti, le scansioni li riconosceranno come ritorno all'originale.
   */
  fun restoreOriginal(media: Media): VersionPlan = plan(media, null, emptyList(), media.originalVersion.id)

  /**
   * Le versioni intermedie (E-E1): ne' l'originale ne' la corrente. Il piano le dichiara opzionali;
   * eliminarle e' una scelta dell'utente, mai automatica.
   */
  fun intermediateVersions(media: Media): List<MediaVersion> =
    media.versions.filter { it.kind != VersionKind.ORIGINAL && it.id != media.currentVersionId }

  /**
   * Quali fasi rifare quando la corrente passa da [from] a [to]. Se tra le due ci sono solo correzioni
   * di data o luogo (in un senso o nell'altro) cambiano solo i metadati: miniatura, anteprima,
   * embedding, OCR e volti restano validi. Altrimenti cambiano i pixel e si rifa' tutto.
   */
  fun stagesToRedo(media: Media, from: VersionId, to: VersionId): Set<WorkStage> = when {
    from == to -> emptySet()
    isMetadataOnlyPath(media, ancestor = from, descendant = to) ||
      isMetadataOnlyPath(media, ancestor = to, descendant = from) -> setOf(WorkStage.META)
    else -> WorkStage.entries.toSet()
  }

  private fun isMetadataOnlyPath(media: Media, ancestor: VersionId, descendant: VersionId): Boolean {
    var step = media.versions.firstOrNull { it.id == descendant } ?: return false
    while (step.isMetadataOnlyCorrection) {
      val parent = step.parentId ?: return false
      if (parent == ancestor) return true
      step = media.versions.firstOrNull { it.id == parent } ?: return false
    }
    return false
  }

  private fun holding(before: MediaLocation, versionId: VersionId, stamp: FileStamp, nowMillis: Long): MediaLocation =
    before.copy(
      versionId = versionId,
      stamp = stamp,
      state = LocationState.PRESENT,
      absentSinceMillis = null,
      // Byte nuovi: la verifica fatta sui byte di prima non vale piu'.
      verifiedAtMillis = null,
      lastSeenAtMillis = nowMillis,
    )

  private fun plan(media: Media, newVersion: MediaVersion?, moved: List<MediaLocation>, newCurrent: VersionId): VersionPlan {
    val byId = moved.associateBy { it.id }
    val updated = media.copy(
      versions = if (newVersion == null) media.versions else media.versions + newVersion,
      locations = media.locations.map { byId[it.id] ?: it },
      currentVersionId = newCurrent,
    )
    return VersionPlan(
      media = updated,
      newVersion = newVersion,
      previousCurrentId = media.currentVersionId,
      changedLocations = moved,
      stagesToRedo = stagesToRedo(updated, media.currentVersionId, newCurrent),
    )
  }
}
