package dev.pampa.mycloudgallery.navigation

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumRefs
import dev.pampa.mycloudgallery.feature.albums.noop.NoAlbumActions
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoriesRefs
import dev.pampa.mycloudgallery.feature.memories.noop.NoMemoriesActions
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.people.contract.NoPeopleRefs
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.people.noop.NoPeopleActions
import dev.pampa.mycloudgallery.feature.people.noop.NoPeopleCrops
import org.junit.Test

/**
 * Fase 10 (P5, D13 (1), (3)): quali voci delle Raccolte hanno la loro pagina nel pannello di dettaglio (da finestra estesa): la pagina di un album, di una persona e di un ricordo, solo se la loro funzione e'
 * legata; le liste, i viaggi, "Nuovo album" e le voci di stato restano pagine (si aprono come sul telefono).
 */
class EntryPanesTest {

  private val album = AlbumId("00000000-0000-4000-8000-000000000001")
  private val person = PersonId("00000000-0000-4000-8000-000000000002")
  private val memory = MemoryId("0123456789abcdefghjkmnpqr0")

  private fun panes(albums: Boolean = true, people: Boolean = true, memories: Boolean = true, resolved: MemoryId? = memory) = EntryPanes(
    albumActions = NoAlbumActions.takeIf { albums },
    albumThumbnails = AlbumThumbnails(NoThumbnailLoader, NoPlaceholderCache, NoAlbumRefs).takeIf { albums },
    peopleActions = NoPeopleActions.takeIf { people },
    peopleThumbnails = PeopleThumbnails(NoThumbnailLoader, NoPlaceholderCache, NoPeopleRefs).takeIf { people },
    peopleCrops = NoPeopleCrops.takeIf { people },
    memoriesActions = NoMemoriesActions.takeIf { memories },
    memoriesThumbnails = MemoriesThumbnails(NoThumbnailLoader, NoPlaceholderCache, NoMemoriesRefs).takeIf { memories },
    resolveMemory = { resolved },
  )

  private val albumCode = AlbumEntryTarget.ALBUM_PREFIX + album.value
  private val smartCode = AlbumEntryTarget.SMART_PREFIX + album.value
  private val personCode = PeopleEntryTarget.PERSON_PREFIX + person.value
  private val memoryCode = MemoriesSectionStates.codeOf(memory)

  @Test
  fun laPaginaDiUnAlbumDiUnaPersonaEDiUnRicordoHaIlPannello() {
    val panes = panes()
    assertThat(panes.pageOf(EntryTarget.of(albumCode))).isEqualTo(PanePage.Album(album))
    assertThat(panes.pageOf(EntryTarget.of(smartCode))).isEqualTo(PanePage.Album(album))
    assertThat(panes.pageOf(EntryTarget.of(personCode))).isEqualTo(PanePage.Person(person))
    assertThat(panes.pageOf(EntryTarget.of(memoryCode))).isEqualTo(PanePage.Memory(memory))
    for (code in listOf(albumCode, smartCode, personCode, memoryCode)) assertThat(hasEntryDetail(code, panes)).isTrue()
  }

  @Test
  fun leListeIViaggiENuovoAlbumRestanoPagine() {
    val panes = panes()
    for (code in listOf(AlbumEntryTarget.ALL_ALBUMS, AlbumEntryTarget.ALL_SMART, AlbumEntryTarget.NEW_ALBUM, AlbumEntryTarget.ALL_TRIPS, PeopleEntryTarget.ALL, PeopleEntryTarget.OTHERS, PeopleEntryTarget.ENABLE, "memories_all", "memories_empty", "sconosciuta")) {
      assertThat(hasEntryDetail(code, panes)).isFalse()
    }
  }

  @Test
  fun senzaLaFunzioneLegataLaVoceSiApreComeSulTelefono() {
    assertThat(hasEntryDetail(albumCode, panes(albums = false))).isFalse()
    assertThat(hasEntryDetail(personCode, panes(people = false))).isFalse()
    assertThat(hasEntryDetail(memoryCode, panes(memories = false))).isFalse()
    // Una funzione spenta non toglie il pannello alle altre.
    assertThat(hasEntryDetail(albumCode, panes(people = false, memories = false))).isTrue()
    // Un ricordo uscito dallo scaffale mentre si toccava: nessuna pagina (la voce non fa niente piu').
    assertThat(hasEntryDetail(memoryCode, panes(resolved = null))).isFalse()
    // Senza `EntryPanes` (i test della navigazione, le anteprime) nessuna di queste voci ha un pannello, come prima.
    for (code in listOf(albumCode, personCode, memoryCode)) assertThat(hasEntryDetail(code)).isFalse()
  }

  @Test
  fun iNomiDelleVociNonPortanoDatiNelToString() {
    assertThat(PanePage.Album(album).toString()).doesNotContain(album.value)
    assertThat(PanePage.Person(person).toString()).doesNotContain(person.value)
    assertThat(PanePage.Memory(memory).toString()).doesNotContain(memory.value)
  }
}
