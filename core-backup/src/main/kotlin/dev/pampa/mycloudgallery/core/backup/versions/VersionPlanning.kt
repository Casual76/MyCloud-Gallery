package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths

/**
 * Le decisioni pure delle versioni (D17, D18, D35, E-E1, E-E5, E-E6), dal catalogo locale soltanto (E-S11): qual e' il file "attuale" di un
 * media sul NAS (il **solito percorso**), che lavoro nasce da una modifica fatta sul telefono, da un file preparato o da "Ripristina
 * originale". Nessun effetto: il motore e i servizi scrivono il diario con cio' che restituisce.
 */
object VersionPlanning {

  /** Il file "attuale" sul NAS di un media: una copia **privata** (della share [share]), presente, fuori da `Versioni/` e dall'area dell'app. */
  class Head(val version: VersionFacts, val copy: NasVersionCopy) {
    override fun toString(): String = "Head(${version.kind.code})"
  }

  /** Una copia che vale come copia privata propria in [share], presente. */
  fun isOwnPrivate(copy: NasVersionCopy, share: String): Boolean =
    copy.present && copy.area == NasArea.PRIVATE && copy.share.equals(share, ignoreCase = true)

  /** Una copia privata propria al solito percorso (non in `Versioni/`, non nell'area dell'app). */
  private fun isUsual(copy: NasVersionCopy, share: String): Boolean =
    isOwnPrivate(copy, share) && NasOpsPaths.isMediaPath(copy.path) && !VersionPaths.isInVersions(copy.path) && !VersionPaths.isVersionsRoot(copy.path)

  /**
   * Il solito percorso del media visto da [forVersion]: si risale la catena da [forVersion] (la sua madre, poi la nonna...) e vale la prima
   * versione con una copia privata al solito percorso; se la catena non ne ha, la versione piu' recente che ne ha una (una modifica fatta
   * dal PC, E-E6, sta su un altro ramo). [exclude] (la versione da salvare) non e' mai il proprio attuale. Null = il media non ha ancora una
   * copia al solito percorso: non e' una versione, la carica il backup della 5A come copia nuova.
   */
  fun head(media: VersionedMedia, share: String, forVersion: VersionId, exclude: VersionId? = forVersion): Head? {
    fun copyOf(v: VersionFacts): NasVersionCopy? = v.nas.filter { isUsual(it, share) }.minByOrNull { it.locationId.value }
    var step = media.version(forVersion)?.parentId
    val seen = HashSet<VersionId>()
    while (step != null && seen.add(step)) {
      val v = media.version(step) ?: break
      if (v.versionId != exclude) copyOf(v)?.let { return Head(v, it) }
      step = v.parentId
    }
    // La versione piu' recente (nell'ordine del catalogo) con una copia al solito percorso.
    for (v in media.versions.asReversed()) {
      if (v.versionId == exclude) continue
      copyOf(v)?.let { return Head(v, it) }
    }
    return null
  }

  /**
   * Una versione del telefono da salvare (un candidato del backup, E-E1, E-E5, E-E6) diventa un lavoro `VERSION` se il media ha gia' un
   * solito percorso nella share [share]: se e' la versione **attuale**, va al solito percorso e l'attuale di prima finisce in `Versioni/`;
   * se non lo e' (un originale rimasto sul telefono dopo una modifica fatta dal PC, una versione intermedia), va **direttamente** in
   * `Versioni/` e il solito percorso non si tocca. Null = non e' una versione (la carica la 5A), o non e' un media proprio.
   */
  fun phoneVersion(media: VersionedMedia, share: String, versionId: VersionId, ref: PhoneRef, stamp: FileStamp): NewVersionJob? {
    if (!media.mine) return null
    val version = media.version(versionId) ?: return null
    val head = head(media, share, versionId) ?: return null
    // La stessa versione gia' al solito percorso: niente da fare (e' gia' sul NAS).
    if (version.nas.any { isOwnPrivate(it, share) }) return null
    val isCurrent = media.currentVersionId == versionId
    return NewVersionJob(
      kind = UploadKind.VERSION,
      mediaId = media.mediaId,
      versionId = versionId,
      size = version.size,
      share = head.copy.share,
      usualPath = head.copy.path,
      source = VersionSource.Phone(ref, stamp),
      target = if (isCurrent) VersionTarget.USUAL_PATH else VersionTarget.VERSIONS_FOLDER,
      current = if (isCurrent) currentFile(head) else null,
    )
  }

  /**
   * Un file preparato (una correzione, D19; il risultato di un editor esterno, E-E3) della versione [versionId] di [media], con SHA-256 noto:
   * va al solito percorso come versione attuale, l'attuale di prima in `Versioni/`. Null se il media non ha un solito percorso proprio.
   */
  fun stagedVersion(media: VersionedMedia, share: String, versionId: VersionId, source: VersionSource.Staged, size: Long): NewVersionJob? {
    if (!media.mine) return null
    val head = head(media, share, versionId) ?: return null
    val isCurrent = media.currentVersionId == versionId
    return NewVersionJob(
      kind = UploadKind.VERSION,
      mediaId = media.mediaId,
      versionId = versionId,
      size = size,
      share = head.copy.share,
      usualPath = head.copy.path,
      source = source,
      target = if (isCurrent) VersionTarget.USUAL_PATH else VersionTarget.VERSIONS_FOLDER,
      current = if (isCurrent) currentFile(head) else null,
      sha256 = source.sha256,
    )
  }

  /**
   * "Ripristina originale" dall'app (D18, E-E10): lo scambio fra il file al solito percorso e l'originale in `Versioni/`. Null se l'originale
   * e' gia' l'attuale o non si puo' (vedi [swapTo]).
   *
   * R3 della revisione 5B: il file da spostare in `Versioni/` deve essere quello della **versione attuale** del catalogo. Se al solito percorso
   * c'e' un'altra versione (l'attuale e' una modifica non ancora salvata, sta solo sul telefono o in un file preparato), lo scambio non si fa:
   * l'attuale resterebbe fuori dal NAS e il ripristino la toglierebbe dal telefono.
   */
  fun swap(media: VersionedMedia, share: String): NewVersionJob? {
    val original = media.original ?: return null
    if (media.currentVersionId == original.versionId) return null
    return swapTo(media, share, original.versionId, from = media.currentVersionId)
  }

  /**
   * Il ritorno a una versione nota fatto sul telefono (Samsung "Ripristina originale", E-E2): il catalogo dice gia' che l'attuale e' una
   * versione che sul NAS sta in `Versioni/`; il solito percorso ha un'altra versione. Sul NAS si **scambiano** le due, senza caricare niente.
   * Null se l'attuale e' gia' al solito percorso o non si puo'.
   */
  fun realign(media: VersionedMedia, share: String): NewVersionJob? = swapTo(media, share, media.currentVersionId)

  /**
   * Lo scambio (`SWAP`, D18): [target] torna al solito percorso dalla sua copia in `Versioni/`, il file che ora sta al solito percorso (di
   * un'altra versione dello **stesso** media) va in `Versioni/`. Entrambi copie private **verificate**: nessuno spostamento di un file di
   * cui non si e' sicuri. Null se [target] e' gia' al solito percorso, se una delle due copie manca o non e' verificata. Con [from] il file al
   * solito percorso deve essere proprio di quella versione (R3: "Ripristina originale" sposta la versione attuale, nessun'altra).
   */
  fun swapTo(media: VersionedMedia, share: String, target: VersionId, from: VersionId? = null): NewVersionJob? {
    if (!media.mine) return null
    val version = media.version(target) ?: return null
    if (version.nas.any { isUsual(it, share) }) return null
    val archived = version.nas
      .filter { isOwnPrivate(it, share) && VersionPaths.isInVersions(it.path) && it.verifiedAtMillis != null }
      .minByOrNull { it.locationId.value } ?: return null
    val heads = media.versions.asReversed().filter { it.versionId != target && (from == null || it.versionId == from) }
    val (headVersion, usual) = heads.firstNotNullOfOrNull { v ->
      v.nas.filter { isUsual(it, share) && it.verifiedAtMillis != null }.minByOrNull { it.locationId.value }?.let { v to it }
    } ?: return null
    return NewVersionJob(
      kind = UploadKind.SWAP,
      mediaId = media.mediaId,
      versionId = target,
      size = version.size,
      share = usual.share,
      usualPath = usual.path,
      source = VersionSource.InVersions(archived.path, archived.stamp, archived.locationId),
      target = VersionTarget.USUAL_PATH,
      current = CurrentFile(headVersion.versionId, usual.locationId, usual.stamp, original = headVersion.kind == VersionKind.ORIGINAL),
      sha256 = version.fullHash,
    )
  }

  private fun currentFile(head: Head): CurrentFile =
    CurrentFile(head.version.versionId, head.copy.locationId, head.copy.stamp, original = head.version.kind == VersionKind.ORIGINAL)
}
