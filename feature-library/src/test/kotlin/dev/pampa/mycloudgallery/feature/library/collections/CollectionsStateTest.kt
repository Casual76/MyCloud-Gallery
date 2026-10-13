package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** L'elenco delle Raccolte, i conteggi, il riepilogo e le azioni sulla selezione (logica pura, su JVM). */
class CollectionsStateTest {

  @Test
  fun leRaccolteSonoQuattroFiltriPoiINascostiEGliEliminatiDiRecente() {
    val state = collectionsUiState(mapOf(CollectionKind.FAVORITES to 3, CollectionKind.VIDEOS to 12), canTrash = true)

    assertThat(state.media.map { it.kind }).containsExactly(
      CollectionKind.FAVORITES, CollectionKind.VIDEOS, CollectionKind.SCREENSHOTS, CollectionKind.WHATSAPP,
    ).inOrder()
    assertThat(state.protectedRows.map { it.kind }).containsExactly(CollectionKind.HIDDEN, CollectionKind.RECENTLY_DELETED).inOrder()
    // Un conteggio mancante e' zero, non un errore.
    assertThat(state.media.first { it.kind == CollectionKind.SCREENSHOTS }.count).isEqualTo(0)
    assertThat(state.media.first { it.kind == CollectionKind.VIDEOS }.count).isEqualTo(12)
  }

  @Test
  fun iNascostiNonDiconoMaiQuantiSono() {
    val state = collectionsUiState(emptyMap(), canTrash = true)
    // E-H1: dall'esterno non si deve nemmeno capire se ce ne sono.
    assertThat(state.protectedRows.first { it.kind == CollectionKind.HIDDEN }.count).isNull()
    assertThat(state.protectedRows.first { it.kind == CollectionKind.RECENTLY_DELETED }.count).isNull()
  }

  @Test
  fun senzaIlCestinoDiSistemaNonCeLaVoceMorta() {
    val state = collectionsUiState(emptyMap(), canTrash = false)

    assertThat(CollectionKind.RECENTLY_DELETED in state).isFalse()
    assertThat(CollectionKind.HIDDEN in state).isTrue()
  }

  @Test
  fun soloLeQuattroRaccolteFiltroHannoUnFiltroDellaLibreria() {
    assertThat(CollectionKind.filters.map { it.filter }).containsExactly(
      LibraryFilter.FAVORITES, LibraryFilter.VIDEOS, LibraryFilter.SCREENSHOTS, LibraryFilter.WHATSAPP,
    ).inOrder()
    assertThat(CollectionKind.HIDDEN.filter).isNull()
    assertThat(CollectionKind.RECENTLY_DELETED.filter).isNull()
  }

  @Test
  fun iCodiciSonoStabiliEUnCodiceSconosciutoNonEUnaRaccolta() {
    assertThat(CollectionKind.entries.map { it.code }).containsExactly(
      "favorites", "videos", "screenshots", "whatsapp", "hidden", "recently_deleted",
    ).inOrder()
    assertThat(CollectionKind.fromCode("whatsapp")).isEqualTo(CollectionKind.WHATSAPP)
    assertThat(CollectionKind.fromCode("album")).isNull()
    assertThat(CollectionKind.fromCode(null)).isNull()
  }

  @Test
  fun iConteggiVengonoDallIndiceVisibileEUnaModificaLiAggiorna() = runTest {
    val world = FakeCollectionsWorld()
    val before = world.source.countsNow()
    assertThat(before.keys).containsExactlyElementsIn(CollectionKind.filters)
    CollectionKind.filters.forEach { kind ->
      assertThat(before[kind]).isEqualTo(world.repository.view(kind.filter!!).value.size)
    }

    // Un video diventa preferito: i Preferiti crescono di uno, gli altri non si muovono.
    val video = idsOf(world.repository.view(LibraryFilter.VIDEOS).value).first { id ->
      val v = world.repository.view(LibraryFilter.FAVORITES).value
      v.viewIndexOf(id) < 0
    }
    world.actions.setFavorite(setOf(video), true)
    val after = world.source.counts().first()
    assertThat(after[CollectionKind.FAVORITES]).isEqualTo(before.getValue(CollectionKind.FAVORITES) + 1)
    assertThat(after[CollectionKind.VIDEOS]).isEqualTo(before[CollectionKind.VIDEOS])
  }

  @Test
  fun ilRiepilogoContaFotoEVideoDellaVista() {
    val library = SyntheticLibrary(seed = 3L, count = 400)
    val view = library.view()
    val videos = (0 until view.size).count { view.snapshot.kindAt(view.snapshotIndex(it)) == MediaKind.VIDEO }

    val (photos, videosCounted) = photoAndVideoCounts(view)

    assertThat(videosCounted).isEqualTo(videos)
    assertThat(photos + videosCounted).isEqualTo(view.size)
    assertThat(videos).isGreaterThan(0)
  }

  @Test
  fun preferitoAggiungeSeAlmenoUnoNonLoEEToglieSeSonoTuttiPreferiti() {
    val world = FakeCollectionsWorld()
    val all = world.repository.view(LibraryFilter.ALL).value
    val favorites = world.repository.view(LibraryFilter.FAVORITES).value
    val favoriteId = idsOf(favorites).first()
    val plainId = idsOf(all).first { !isFavorite(all, it) }
    assertThat(isFavorite(all, favoriteId)).isTrue()
    assertThat(isFavorite(all, plainId)).isFalse()

    val calls = mutableListOf<Pair<Set<*>, Boolean>>()
    val hides = mutableListOf<Set<*>>()
    val onlyFavorites = collectionSelectionActions(all, setOf(favoriteId), { ids, on -> calls += ids to on }, { hides += it })
    val mixed = collectionSelectionActions(all, setOf(favoriteId, plainId), { ids, on -> calls += ids to on }, { hides += it })

    onlyFavorites[0].run(setOf(favoriteId))
    mixed[0].run(setOf(favoriteId, plainId))
    mixed[1].run(setOf(plainId))

    assertThat(calls).containsExactly(setOf(favoriteId) to false, setOf(favoriteId, plainId) to true).inOrder()
    assertThat(hides).containsExactly(setOf(plainId))
    assertThat(onlyFavorites.map { it.labelRes }).isNotEqualTo(mixed.map { it.labelRes })
  }

  @Test
  fun unElementoFuoriDallaVistaNonEUnPreferito() {
    val world = FakeCollectionsWorld()
    val favorites = world.repository.view(LibraryFilter.FAVORITES).value
    val hiddenFavorite = world.hiddenIds.first()

    assertThat(isFavorite(favorites, hiddenFavorite)).isFalse()
  }
}
