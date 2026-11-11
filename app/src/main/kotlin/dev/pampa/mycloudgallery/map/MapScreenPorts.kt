package dev.pampa.mycloudgallery.map

import android.content.res.Resources
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.map.places.MapSectionData
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionModel
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionSources
import dev.pampa.mycloudgallery.navigation.FlowSection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** La trasformazione campo per campo dei dati di feature-map nel gruppo delle Raccolte (i due moduli non si conoscono; `PlacesSectionTest`). */
internal fun MapSectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
  title = title,
  entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing, coverPlace = it.coverPlace, coverPlaces = it.coverPlaces) },
  code = code,
  hideable = hideable,
  hint = hint,
)

/**
 * La sezione Luoghi delle Raccolte come fornitore (codice `places`, P4, D30): la riga "Mappa" c'e' **subito** (senza numeri); i conti e i luoghi arrivano
 * dall'indice dei punti, che si costruisce **al primo sguardo** (`Lazily`: chi guarda la sezione la fa partire; mai all'avvio dell'app). **Una per processo**.
 */
fun placesSectionProvider(resources: Resources, map: MapGraph): CollectionsSectionProvider {
  val sources = PlacesSectionSources(resources, PlacesSectionModel(map.points, map.places, map.scope), map.scope)
  return FlowSection(
    PlacesSectionSources.CODE,
    sources.section.map { it.toGroup() }.stateIn(map.scope, SharingStarted.Lazily, sources.section.value.toGroup()),
  )
}
