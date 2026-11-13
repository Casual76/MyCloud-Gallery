package dev.pampa.mycloudgallery.feature.search.models

import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import java.util.UUID

/** Richieste di una singola corsia, solo mentre il suo lifecycle e' STARTED. */
internal class AnalysisThumbnailLease(private val thumbnails: SearchThumbnails?) {
  private val owner = "analysis-lane-${UUID.randomUUID()}"
  private var active = false
  private var requests = emptyList<ThumbRequest>()

  fun update(value: List<ThumbRequest>) {
    requests = value
    if (active) thumbnails?.holdAnalysis(requests, owner)
  }

  fun started(value: Boolean) {
    active = value
    if (value) thumbnails?.holdAnalysis(requests, owner) else thumbnails?.releaseAnalysis(owner)
  }
}
