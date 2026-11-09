package dev.pampa.mycloudgallery.feature.albums

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumsTabKeys
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbums
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabOrder
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.SpecialSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le scene della scheda Album (G2b) sopra quelle degli album ([Scenes]): le voci speciali vengono da una libreria sintetica (le stesse viste filtrate
 * della Libreria: Preferiti, Fotocamera, Video, Screenshot, WhatsApp), gli album dal mondo finto (miei, smart, condivisi miei e di altri).
 */
class AlbumsTabScenes(val scenes: Scenes = Scenes()) {

  val library = SyntheticLibrary(seed = 11L, count = 1_500)

  /** Le viste speciali vive: un test le puo' cambiare ([views]). */
  val views: Map<SpecialAlbum, MutableStateFlow<TimelineView>> =
    SpecialAlbum.entries.associateWith { kind -> MutableStateFlow(library.view(kind.filter)) }

  val specials: SpecialAlbums = SpecialAlbums { kind -> views.getValue(kind) as StateFlow<TimelineView> }

  /** Le viste di un profilo del tablet senza fotocamera ne' messaggi: tutto vuoto. */
  val emptySpecials: SpecialAlbums = SpecialAlbums { MutableStateFlow(EmptyTimelineView(LibraryFilter.ALL)) }

  fun summaries(source: SpecialAlbums = specials): Map<SpecialAlbum, SpecialSummary> =
    SpecialAlbum.entries.associateWith { SpecialSummary.of(source.view(it).value) }

  /** Le aperture della scheda piena: un condiviso di Marco, Screenshot, poi lo smart di Oslo. */
  val openings: Map<String, Long>
    get() = mapOf(
      AlbumsTabKeys.album(scenes.christmas) to 30L,
      AlbumsTabKeys.special(SpecialAlbum.SCREENSHOTS) to 20L,
      AlbumsTabKeys.album(scenes.oslo) to 10L,
    )

  /** La scheda piena: tutte le voci speciali, gli album miei, smart e condivisi, qualcuno aperto di recente. */
  fun full(): AlbumsTabState.Ready {
    val (entries, pinned) = AlbumsTabOrder.arrange(scenes.world.albums.all.value.albums, summaries(), openings)
    return AlbumsTabState.Ready(entries, pinned)
  }

  /** La scheda quasi vuota: un profilo del tablet senza foto della fotocamera, senza messaggi e senza album. Restano i Preferiti, che spiegano. */
  fun almostEmpty(): AlbumsTabState.Ready {
    val (entries, pinned) = AlbumsTabOrder.arrange(emptyList(), summaries(emptySpecials), emptyMap())
    return AlbumsTabState.Ready(entries, pinned)
  }

  /** Gli album non ancora letti: i fissi e le copertine ferme. */
  fun loading(): AlbumsTabState.Ready {
    val (entries, pinned) = AlbumsTabOrder.arrange(emptyList(), summaries(), emptyMap())
    return AlbumsTabState.Ready(entries.take(pinned), pinned, albumsPending = true)
  }
}
