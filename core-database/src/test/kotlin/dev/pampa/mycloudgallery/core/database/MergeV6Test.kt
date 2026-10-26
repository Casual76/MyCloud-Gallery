package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumExclusionEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumRuleEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareOpEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.database.entity.TripProposalEntity
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'unione di due identita' non perde nessuna riga delle tabelle della v6 che puntano a un media (Fase 7, P1): elementi degli album con
 * **tutte** le loro colonne (come sono entrati, impronta, chi li ha aggiunti), foto d'esempio delle regole, esclusioni, suggeriti, diario
 * della condivisione e copertina di una proposta di viaggio. `MergeReferencesTest` controlla che l'elenco dei riferimenti coincida con lo
 * schema; qui si prova che l'unione li sposti davvero.
 */
@RunWith(AndroidJUnit4::class)
class MergeV6Test : CatalogTestBase() {

  /** Due media con gli stessi byte ma nati separati (impronta rapida diversa): si uniscono con `merge`; il minore sopravvive. */
  private suspend fun twins(n: Int): Pair<MediaId, MediaId> {
    val full = smallFull(n)
    val other = full.copy(quickHash = QuickHash("f".repeat(64)))
    val a = catalog.record(phoneObservation("IMG_${n}_a.jpg", 100L + n, full, T0 - n)).mediaId!!
    val b = catalog.record(phoneObservation("IMG_${n}_b.jpg", 200L + n, other, T0 - n)).mediaId!!
    check(a != b) { "I gemelli dovevano nascere separati." }
    return a to b
  }

  private suspend fun album(id: String, kind: String = "manual") =
    db.albumDao().insert(AlbumEntity(id, "Titolo $id", coverMediaId = null, createdAt = T0, updatedAt = T0, kind = kind))

  @Test
  fun unUnioneSpostaSulSopravvissutoOgniRigaDegliAlbumDelDiarioEDelleProposte() = runBlocking<Unit> {
    val (a, b) = twins(7)
    val survivor = minOf(a, b)
    val loser = maxOf(a, b)
    album("smart", kind = "smart")
    album("manuale")
    val albums = db.albumDao()
    // Il perdente e' in due album (con l'origine, l'impronta e chi l'ha aggiunto); il sopravvissuto e' gia' in uno dei due, a modo suo.
    albums.upsertItems(
      listOf(
        AlbumItemEntity("smart", loser.value, 3000, T0, origin = "accepted", fp = "qh:1", addedBy = "marco"),
        AlbumItemEntity("manuale", loser.value, 1000, T0 + 1, origin = "manual", fp = "qh:1", addedBy = "marco"),
        AlbumItemEntity("manuale", survivor.value, 2000, T0 + 2, origin = "manual", fp = "qh:1", addedBy = "alessio"),
      ),
    )
    val rules = db.albumRuleDao()
    rules.upsertAll(
      listOf(
        AlbumRuleEntity("smart", "period", 0, "20260301-20260307"),
        AlbumRuleEntity("smart", "example", 0, mediaId = loser.value),
        AlbumRuleEntity("smart", "example", 1, mediaId = survivor.value),
      ),
    )
    val choices = db.albumChoiceDao()
    choices.upsertExclusions(listOf(AlbumExclusionEntity("smart", loser.value, "qh:1")))
    choices.upsertSuggestions(listOf(AlbumSuggestionEntity("smart", loser.value, "s1", T0)))
    // Il diario: l'operazione non punta ai media, i suoi elementi si'.
    val diary = db.shareDiaryDao()
    diary.insertOp(
      AlbumShareOpEntity("op-1", "smart", "share", "piano", "Titolo", "add", null, "NAS", "Album condivisi/Titolo", "leave_out", false, T0, T0),
    )
    diary.insertItems(listOf(AlbumShareItemEntity("op-1", 0, loser.value, "move_in", "transferring", true, 2_000, updatedAt = T0)))
    db.tripDao().upsertProposals(
      listOf(TripProposalEntity("t".repeat(64), "trip", 20260301, 20260307, "Oslo", 3, loser.value, "1,2", null, T0)),
    )

    val outcome = catalog.merge(a, b)
    assertThat(outcome).isEqualTo(MediaCatalog.MergeOutcome.Merged(survivor, loser))

    // Elementi: quello del perdente passa con le sue colonne dove il sopravvissuto non c'era; dove c'era resta il suo.
    val smartItem = albums.items("smart").single()
    assertThat(smartItem.mediaId).isEqualTo(survivor.value)
    assertThat(listOf(smartItem.position, smartItem.origin, smartItem.fp, smartItem.addedBy)).containsExactly(3000L, "accepted", "qh:1", "marco").inOrder()
    val manualItem = albums.items("manuale").single()
    assertThat(listOf(manualItem.mediaId, manualItem.addedBy, manualItem.position)).containsExactly(survivor.value, "alessio", 2000L).inOrder()
    // Regole: la foto d'esempio del perdente e' del sopravvissuto (due righe, una per posizione); le altre non si toccano.
    assertThat(rules.rules("smart").map { Triple(it.rule, it.position, it.mediaId) }).containsExactly(
      Triple("example", 0, survivor.value),
      Triple("example", 1, survivor.value),
      Triple("period", 0, null),
    ).inOrder()
    assertThat(choices.exclusions("smart").single().mediaId).isEqualTo(survivor.value)
    assertThat(choices.suggestions("smart").single().mediaId).isEqualTo(survivor.value)
    assertThat(diary.items("op-1").single().mediaId).isEqualTo(survivor.value)
    assertThat(db.tripDao().proposals().single().coverMediaId).isEqualTo(survivor.value)
    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
  }

  /** Un media che si rinomina o si cancella non lascia righe orfane: gli album e i loro derivati se ne vanno in cascata. */
  @Test
  fun unAlbumCancellatoPortaViaLeSueRegoleEsclusioniESuggeriti() = runBlocking<Unit> {
    val (a, _) = twins(8)
    album("smart", kind = "smart")
    db.albumRuleDao().upsertAll(listOf(AlbumRuleEntity("smart", "example", 0, mediaId = a.value), AlbumRuleEntity("smart", "text", 0, "mare")))
    db.albumChoiceDao().upsertExclusions(listOf(AlbumExclusionEntity("smart", a.value)))
    db.albumChoiceDao().upsertSuggestions(listOf(AlbumSuggestionEntity("smart", a.value, "s2", T0)))
    db.albumDao().upsertItem(AlbumItemEntity("smart", a.value, 1000, T0))

    db.albumDao().delete("smart")

    assertThat(db.albumRuleDao().rules("smart")).isEmpty()
    assertThat(db.albumChoiceDao().exclusions("smart")).isEmpty()
    assertThat(db.albumChoiceDao().suggestions("smart")).isEmpty()
    assertThat(db.albumDao().items("smart")).isEmpty()
    assertThat(db.mediaDao().get(a.value)).isNotNull()
  }
}
