package dev.pampa.mycloudgallery.feature.map.ui

import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails

// Le MINIATURE della mappa (Fase 9, P4; §2.10 "Bolle": miniature da `core-imaging`, replica locale, mai il NAS durante il gesto). Come `thumbRequestOf` delle altre schermate:
// la chiave e' l'id con la versione della cella, la sorgente la dice la porta ([MapThumbnails.refs]); senza una sorgente locale resta il segnaposto sfocato.

private const val DEFAULT_VERSION_KEY: Long = 1L

/** La richiesta di miniatura per la cella [index] dell'istantanea, o null se il media non ha una sorgente locale. */
internal fun thumbRequestOf(thumbnails: MapThumbnails, snapshot: TimelineSnapshot, index: Int, bucket: ThumbBucket = ThumbBucket.M): ThumbRequest? {
  val ref = thumbnails.refs.refAt(snapshot, index) ?: return null
  val version = if (snapshot is ColumnarTimelineSnapshot) snapshot.versionKeyAt(index) else DEFAULT_VERSION_KEY
  return ThumbRequest(ThumbKey(snapshot.idAt(index), version, bucket), ref)
}

/** La richiesta di miniatura della copertina [media] nell'istantanea (per `loader.window`), o null se la Libreria non ha la cella o non c'e' una sorgente locale. */
internal fun coverRequest(thumbnails: MapThumbnails, snapshot: TimelineSnapshot?, media: MediaId, bucket: ThumbBucket = ThumbBucket.S): ThumbRequest? {
  if (snapshot == null) return null
  val index = snapshot.indexOf(media)
  return if (index >= 0) thumbRequestOf(thumbnails, snapshot, index, bucket) else null
}
