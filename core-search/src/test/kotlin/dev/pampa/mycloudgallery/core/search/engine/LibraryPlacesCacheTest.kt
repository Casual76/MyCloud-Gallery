package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import org.junit.Test

class LibraryPlacesCacheTest {
  private val base = SyntheticLibrary(seed = 7, count = 4).snapshot
  private fun snapshot(vararg ids: Int) = object : TimelineSnapshot by base {
    override val size = ids.size
    override fun placeAt(i: Int) = ids[i]
  }

  @Test fun unaNuovaIstantaneaConGliStessiLuoghiRiusaDizionarioETop() {
    var lookups = 0
    val places = LibraryPlaces(PlaceLookup { id -> lookups++; PlaceName("Luogo $id", "Regione", "Italia") })
    val first = snapshot(1, 2, 1, 0)
    val dictionary = places.dictionary(first)
    val top = places.top(first)
    val calls = lookups
    // Ordine e metadati diversi, stessi conteggi dei luoghi visibili.
    val second = snapshot(0, 1, 2, 1)
    assertThat(places.dictionary(second)).isSameInstanceAs(dictionary)
    assertThat(places.top(second)).isEqualTo(top)
    assertThat(lookups).isEqualTo(calls)
  }

  @Test fun ilCambioDeiConteggiONascondereLultimaFotoInvalidaLaCache() {
    val places = LibraryPlaces(PlaceLookup { id -> PlaceName("Luogo $id", null, "Italia") })
    val first = places.dictionary(snapshot(1, 1, 2))
    val changed = snapshot(1, 2, 2)
    assertThat(places.dictionary(changed)).isNotSameInstanceAs(first)
    assertThat(places.top(changed).first().ids).containsExactly(2)
    val removed = snapshot(1, 0)
    assertThat(places.dictionary(removed).entries.flatMap { it.option.ids }).doesNotContain(2)
    assertThat(places.top(removed).single().ids).containsExactly(1)
  }
}
