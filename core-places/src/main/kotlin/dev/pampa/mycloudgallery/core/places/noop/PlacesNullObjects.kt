package dev.pampa.mycloudgallery.core.places.noop

import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.Place
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.PlaceMatch
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.ZoneId

// I null-object di core-places: cio' che :app lega finche' P4 non porta il dataset vero.
//
// Non stanno nel package `testing` di proposito: vanno nella release, mentre i fake no (tools/check.ps1 lo
// verifica). Falliscono chiusi: nessun luogo, nessun fuso, nessun nome. Con questi la Libreria funziona lo stesso
// (le intestazioni non hanno luoghi), e nessuna foto riceve un luogo sbagliato.

/** Nessun dataset: non trova mai un luogo. `dataset` vale 0, che nessun `PlaceRef` puo' avere. */
object NoReverseGeocoder : ReverseGeocoder {
  override val dataset: Int = 0

  override fun nearest(p: GeoPoint): PlaceMatch? = null

  override fun place(id: PlaceId): Place? = null
}

/** Nessun fuso noto: i chiamanti ripiegano sui vicini o sul fuso del telefono alla lettura. */
object NoTimeZoneLookup : TimeZoneLookup {
  override fun zoneAt(p: GeoPoint): ZoneId? = null
}

/** Nessun nome: le intestazioni escono senza luoghi. */
object NoPlaceNames : PlaceNames {
  override fun label(ref: PlaceRef): String? = null

  override fun labelOfId(geonameId: Int): String? = null

  override fun summary(top: IntArray, distinct: Int): String = ""
}
