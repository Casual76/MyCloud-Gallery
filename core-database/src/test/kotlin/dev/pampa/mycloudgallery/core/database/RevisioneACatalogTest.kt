package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.nas
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.quick
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Revisione A della Fase 6 nel catalogo: le chiavi di contenuto per il viewer anche dei nascosti e dei membri di un gruppo (VT-1, I12,
 * D29) e il filtro finale dei nascosti della ricerca a blocchi (CE-6), con la stessa risposta della lettura per media di prima.
 */
@RunWith(AndroidJUnit4::class)
class RevisioneACatalogTest : CatalogTestBase() {

  private suspend fun phone(n: Int) = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), T0 - n)).mediaId!!

  private fun key(seed: Int): ContentKey = ContentKey.of(smallFull(seed))

  @Test
  fun vt1_leChiaviDelViewerValgonoAncheNeiNascostiENeiMembriDiUnGruppo() = runBlocking<Unit> {
    val cover = phone(1)
    val member = phone(2)
    val hidden = phone(3)
    catalog.group(MediaGroupKind.BURST, listOf(cover, member), cover)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    val edited = smallFull(4)
    catalog.addEditedVersion(hidden, catalog.versionOf(hidden), edited.fullHash!!, edited.quickHash, edited.size)

    // Le celle (Libreria, ricerca) restano senza nascosti ne' membri (D29): e' la lettura di prima, che nel viewer dei Nascosti non dava niente.
    assertThat(catalog.cellContentKeysOf(listOf(hidden, member)).map { it.mediaId }).isEmpty()

    val viewer = catalog.mediaContentKeysOf(listOf(hidden, member, cover, MediaId.random()))
    assertThat(viewer.map { it.mediaId }).containsExactly(cover, member, hidden)
    assertThat(viewer.single { it.mediaId == hidden }.own).containsExactly(key(4), key(3)).inOrder()
    assertThat(viewer.single { it.mediaId == member }.own).containsExactly(key(2))
    // Per il viewer conta il media aperto: la copertina non porta i membri.
    assertThat(viewer.single { it.mediaId == cover }.members).isEmpty()
  }

  /** La regola di prima, per media: nascosto se lo e' lui o il media in cui e' stato unito. */
  private suspend fun hiddenOneByOne(ids: Collection<MediaId>): Set<MediaId> {
    val personal = db.personalDao()
    return ids.filterTo(HashSet()) { id ->
      val resolved = catalog.resolve(id) ?: id
      personal.get(resolved.value)?.hidden == true || personal.get(id.value)?.hidden == true
    }
  }

  @Test
  fun ce6_iNascostiSiTrovanoABlocchiConLaStessaRispostaDiPrima() = runBlocking<Unit> {
    val visible = (1..5).map { phone(it) }
    val hidden = (6..8).map { phone(it) }
    hidden.forEach { catalog.setPersonalFlags(it, PersonalFlags(hidden = true)) }
    // Due copie dello stesso file unite: l'id assorbito (alias) e' nascosto se lo e' il media unito.
    val a = catalog.record(obs(nas("A/x.jpg"), quick(9))).mediaId!!
    val b = catalog.record(obs(nas("B/x.jpg"), quick(9)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    catalog.setPersonalFlags(a, PersonalFlags(hidden = true))
    catalog.record(obs(nas("A/x.jpg"), full(9)))
    catalog.record(obs(nas("B/x.jpg"), full(9)))
    val merged = catalog.merge(a, b) as MediaCatalog.MergeOutcome.Merged

    val ids = visible + hidden + listOf(merged.survivor, merged.loser, MediaId.random())
    val expected = hiddenOneByOne(ids)
    assertThat(expected).containsExactlyElementsIn(hidden + listOf(merged.survivor, merged.loser))
    assertThat(catalog.hiddenAmong(ids)).isEqualTo(expected)
    assertThat(catalog.hiddenAmong(emptyList())).isEmpty()
  }

  @Test
  fun ce6_oltreIlLimiteDiSqliteSiLeggeABlocchi() = runBlocking<Unit> {
    val hidden = phone(1)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    val many = List(3 * 900) { MediaId.random() } + hidden
    assertThat(catalog.hiddenAmong(many)).containsExactly(hidden)
  }
}
