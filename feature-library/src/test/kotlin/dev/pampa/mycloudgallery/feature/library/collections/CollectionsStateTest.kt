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
  fun laSchedaFaDaSolaSoloIPostiProtetti() {
    // CS-17: le raccolte-filtro del vecchio "Telefono" stanno nella scheda Album (G2): la scheda fa da sola i Nascosti e gli Eliminati di recente.
    val state = collectionsUiState(canTrash = true)
    assertThat(state.protectedRows.map { it.kind }).containsExactly(CollectionKind.HIDDEN, CollectionKind.RECENTLY_DELETED).inOrder()
    assertThat(builtinSections(state).map { it.code }).containsExactly(CollectionSections.SAFE, CollectionSections.HIDDEN).inOrder()
  }

  @Test
  fun iNascostiNonDiconoMaiQuantiSono() {
    val state = collectionsUiState(canTrash = true)
    // E-H1: dall'esterno non si deve nemmeno capire se ce ne sono.
    assertThat(state.protectedRows.first { it.kind == CollectionKind.HIDDEN }.count).isNull()
    assertThat(state.protectedRows.first { it.kind == CollectionKind.RECENTLY_DELETED }.count).isNull()
  }

  @Test
  fun senzaIlCestinoDiSistemaNonCeLaVoceMorta() {
    val state = collectionsUiState(canTrash = false)

    assertThat(CollectionKind.RECENTLY_DELETED in state).isFalse()
    assertThat(CollectionKind.HIDDEN in state).isTrue()
  }

  @Test
  fun soloLeRaccolteFiltroHannoUnFiltroDellaLibreria() {
    // G2b: la raccolta "whatsapp" mostra la Messaggistica (WhatsApp, Telegram e Signal); la Fotocamera e' nuova.
    assertThat(CollectionKind.filters.map { it.filter }).containsExactly(
      LibraryFilter.FAVORITES, LibraryFilter.CAMERA, LibraryFilter.VIDEOS, LibraryFilter.SCREENSHOTS, LibraryFilter.MESSAGING,
    ).inOrder()
    assertThat(CollectionKind.HIDDEN.filter).isNull()
    assertThat(CollectionKind.RECENTLY_DELETED.filter).isNull()
  }

  @Test
  fun iCodiciSonoStabiliEUnCodiceSconosciutoNonEUnaRaccolta() {
    // I codici di prima non cambiano (stanno nello stato salvato e nell'ordine sincronizzato delle Raccolte); "camera" e' nuovo.
    assertThat(CollectionKind.entries.map { it.code }).containsExactly(
      "favorites", "camera", "videos", "screenshots", "whatsapp", "hidden", "recently_deleted",
    ).inOrder()
    assertThat(CollectionKind.fromCode("whatsapp")).isEqualTo(CollectionKind.WHATSAPP)
    assertThat(CollectionKind.fromCode("camera")).isEqualTo(CollectionKind.CAMERA)
    assertThat(CollectionKind.fromCode("album")).isNull()
    assertThat(CollectionKind.fromCode(null)).isNull()
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
