package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * LA SUITE DI CONTRATTO di [Albums] (docs/fase7-progetto.md §2.1, D3, D5, D12, D21, D22, D39; §5.2). La passano l'esecutore di riferimento
 * (`InMemoryAlbums`) e, da P3 in poi, gli album veri (proiezione dei dati personali sulle tabelle v6): ogni implementazione fornisce un
 * [World] sopra la stessa Libreria finta. Le attese sono con un tetto di tempo, cosi' un'implementazione asincrona passa come una sincrona.
 */
abstract class AlbumsContract {

  /** Il mondo di prova: gli album sopra la Libreria finta. [smart] per gli album smart (i membri li decide il test); null se non provati. */
  interface World {
    val albums: Albums
    val library: FakeAlbumLibrary
    val choices: SmartChoices?

    /** I membri degli album smart secondo le regole (il motore e' di P6): null se il mondo non li sa fingere. */
    fun setSmart(confident: List<MediaId>, suggested: List<MediaId>) {}

    /** Un album condiviso attivo (creato da un altro account, livello "Gli altri aggiungono") e mostrato qui; null se non provato. */
    fun sharedAlbum(): AlbumId? = null
  }

  protected abstract fun world(): World

  private val base = 1_767_225_600_000L // 1/1/2026 00:00 UTC

  protected fun media(n: Int, hoursFromBase: Int = n, hidden: Boolean = false) =
    FakeMedia(FakeAlbumLibrary.id(n), base + hoursFromBase * 3_600_000L, hidden = hidden, pixels = 1_000_000L + n)

  private fun run(block: suspend () -> Unit): Unit = runBlocking { withTimeout(10_000) { block() } }

  private suspend fun World.album(id: AlbumId): Album = albums.all.first { list -> list.byId(id) != null }.byId(id)!!

  private suspend fun World.awaitAlbum(id: AlbumId, check: (Album) -> Boolean): Album =
    albums.all.first { list -> list.byId(id)?.let(check) == true }.byId(id)!!

  private suspend fun World.items(id: AlbumId): List<MediaId> {
    val content = albums.content(id).first { it != null }!!
    return (0 until content.items.size).map { content.items.snapshot.idAt(content.items.snapshotIndex(it)) }
  }

  private suspend fun World.create(title: String, kind: AlbumKind = AlbumKind.MANUAL, rules: SmartRules? = null): AlbumId =
    (albums.create(title, kind, rules) as CreateOutcome.Created).id

  @Test
  fun unAlbumManualeNuovoCompareConIPredefiniti() = run {
    val w = world()
    val id = w.create("  Mare  ")
    val album = w.album(id)
    assertThat(album).isInstanceOf(Album.Manual::class.java)
    assertThat(album.title).isEqualTo("Mare")
    assertThat(album.count).isEqualTo(0)
    assertThat(album.sort).isEqualTo(AlbumSort.NEWEST_FIRST)
    assertThat(album.cover).isEqualTo(CoverRef.None)
  }

  @Test
  fun unTitoloNonValidoNonCreaNiente() = run {
    val w = world()
    assertThat(w.albums.create("   ", AlbumKind.MANUAL)).isEqualTo(CreateOutcome.Invalid(TitleProblem.EMPTY))
    assertThat(w.albums.create("x".repeat(65), AlbumKind.MANUAL)).isEqualTo(CreateOutcome.Invalid(TitleProblem.TOO_LONG))
    assertThat(w.albums.create("Oslo‮", AlbumKind.MANUAL)).isEqualTo(CreateOutcome.Invalid(TitleProblem.FORBIDDEN_CHARS))
    assertThat(w.albums.all.value.albums).isEmpty()
  }

  /** D4, D5: aggiungere e' idempotente; il conteggio e il contenuto sono senza nascosti (D22) e tornano quando il media torna visibile. */
  @Test
  fun aggiungereEIdempotenteEINascostiNonSiContanoMai() = run {
    val w = world()
    w.library.put(media(1), media(2), media(3))
    val id = w.create("Gita")
    assertThat(w.albums.add(id, listOf(media(1).id, media(2).id))).isEqualTo(AddOutcome.Added(2, 0))
    assertThat(w.albums.add(id, listOf(media(2).id, media(3).id))).isEqualTo(AddOutcome.Added(1, 1))
    assertThat(w.awaitAlbum(id) { it.count == 3 }.count).isEqualTo(3)
    w.library.hide(media(2).id)
    assertThat(w.awaitAlbum(id) { it.count == 2 }.cover.media).isNotEqualTo(media(2).id)
    assertThat(w.items(id)).doesNotContain(media(2).id)
    w.library.unhide(media(2).id)
    assertThat(w.awaitAlbum(id) { it.count == 3 }.count).isEqualTo(3)
  }

  /** D22: dai Nascosti l'album riceve il media ma non lo mostra finche' e' nascosto. */
  @Test
  fun unNascostoAggiuntoNonSiMostraFinchéENascosto() = run {
    val w = world()
    w.library.put(media(1, hidden = true), media(2))
    val id = w.create("Privato")
    assertThat(w.albums.add(id, listOf(media(1).id, media(2).id))).isEqualTo(AddOutcome.Added(2, 0))
    assertThat(w.awaitAlbum(id) { it.count == 1 }.cover.media).isEqualTo(media(2).id)
    assertThat(w.items(id)).containsExactly(media(2).id)
  }

  /** D21, E-H6: una copertina scelta e poi nascosta diventa automatica; tolta dall'album, pure. */
  @Test
  fun laCopertinaNascostaOToltaDiventaAutomatica() = run {
    val w = world()
    w.library.put(media(1), media(2), media(3))
    val id = w.create("Copertine")
    w.albums.add(id, listOf(media(1).id, media(2).id, media(3).id))
    assertThat(w.albums.setCover(id, media(1).id)).isEqualTo(EditOutcome.Done)
    assertThat(w.awaitAlbum(id) { it.cover is CoverRef.Chosen }.cover).isEqualTo(CoverRef.Chosen(media(1).id))
    w.library.hide(media(1).id)
    val auto = w.awaitAlbum(id) { it.cover is CoverRef.Auto }.cover
    assertThat(auto.media).isNotEqualTo(media(1).id)
    // Il manuale: l'ultimo aggiunto.
    assertThat(auto.media).isEqualTo(media(3).id)
    assertThat(w.albums.setCover(id, MediaId("00000000-0000-4000-8000-00000000abcd"))).isInstanceOf(EditOutcome.Invalid::class.java)
  }

  /** D39: togliere non e' eliminare. Il media resta nella Libreria. */
  @Test
  fun togliereDallAlbumNonToccaIlMedia() = run {
    val w = world()
    w.library.put(media(1), media(2))
    val id = w.create("Togli")
    w.albums.add(id, listOf(media(1).id, media(2).id))
    assertThat(w.albums.remove(id, listOf(media(1).id))).isEqualTo(EditOutcome.Done)
    assertThat(w.awaitAlbum(id) { it.count == 1 }.count).isEqualTo(1)
    assertThat(w.library[media(1).id]).isNotNull()
    assertThat(w.albums.delete(id)).isEqualTo(EditOutcome.Done)
    w.albums.all.first { it.byId(id) == null }
    assertThat(w.library.all()).hasSize(2)
  }

  /** D21: dal piu' recente, dal piu' vecchio, personalizzato con riordino. */
  @Test
  fun gliOrdiniDentroLAlbum() = run {
    val w = world()
    w.library.put(media(1, 10), media(2, 20), media(3, 30))
    val id = w.create("Ordini")
    w.albums.add(id, listOf(media(2).id, media(1).id, media(3).id))
    assertThat(w.items(id)).containsExactly(media(3).id, media(2).id, media(1).id).inOrder()
    w.albums.setSort(id, AlbumSort.OLDEST_FIRST)
    w.awaitAlbum(id) { it.sort == AlbumSort.OLDEST_FIRST }
    assertThat(w.items(id)).containsExactly(media(1).id, media(2).id, media(3).id).inOrder()
    w.albums.setSort(id, AlbumSort.CUSTOM)
    w.awaitAlbum(id) { it.sort == AlbumSort.CUSTOM }
    assertThat(w.items(id)).containsExactly(media(2).id, media(1).id, media(3).id).inOrder()
    assertThat(w.albums.reorder(id, media(3).id, null)).isEqualTo(EditOutcome.Done)
    assertThat(w.albums.reorder(id, media(2).id, media(1).id)).isEqualTo(EditOutcome.Done)
    assertThat(w.items(id)).containsExactly(media(3).id, media(1).id, media(2).id).inOrder()
  }

  @Test
  fun rinominaEOrdineDellElenco() = run {
    val w = world()
    val b = w.create("Bari")
    val a = w.create("Àrezzo")
    assertThat(w.albums.rename(b, "")).isEqualTo(EditOutcome.Invalid(TitleProblem.EMPTY))
    assertThat(w.albums.rename(b, "Zagabria")).isEqualTo(EditOutcome.Done)
    w.albums.setListSort(AlbumListSort.NAME)
    val byName = w.albums.all.first { it.sort == AlbumListSort.NAME && it.byId(b)?.title == "Zagabria" }
    assertThat(byName.albums.map { it.id }).containsExactly(a, b).inOrder()
    w.albums.setListSort(AlbumListSort.CUSTOM)
    assertThat(w.albums.moveInList(b, null)).isEqualTo(EditOutcome.Done)
    val custom = w.albums.all.first { it.sort == AlbumListSort.CUSTOM && it.albums.firstOrNull()?.id == b }
    assertThat(custom.albums.map { it.id }).containsExactly(b, a).inOrder()
    assertThat(w.albums.rename(AlbumId("00000000-0000-4000-8000-000000000777"), "X")).isEqualTo(EditOutcome.NotFound)
  }

  /** D12, D35: un album condiviso non riceve media direttamente: c'e' il piano da confermare, e niente e' cambiato. */
  @Test
  fun aggiungereAUnCondivisoVuoleLaConferma() = run {
    val w = world()
    val shared = w.sharedAlbum() ?: return@run
    w.library.put(media(1))
    val before = w.album(shared).count
    val out = w.albums.add(shared, listOf(media(1).id))
    assertThat(out).isInstanceOf(AddOutcome.NeedsShareConfirmation::class.java)
    assertThat((out as AddOutcome.NeedsShareConfirmation).plan.items.map { it.media }).containsExactly(media(1).id)
    assertThat(w.album(shared).count).isEqualTo(before)
    assertThat(w.albums.delete(shared)).isEqualTo(EditOutcome.NotAllowed(AlbumOp.DELETE_ALBUM))
  }

  /** D14, D16, E-AL5: escludere vale per sempre, accettare fa entrare un suggerito, congelare fissa gli elementi. */
  @Test
  fun leSceltePersonaliDegliAlbumSmart() = run {
    val w = world()
    val choices = w.choices ?: return@run
    w.library.put(media(1), media(2), media(3), media(4))
    w.setSmart(confident = listOf(media(1).id, media(2).id), suggested = listOf(media(3).id))
    val id = w.create("Oslo 2026", AlbumKind.SMART, SmartRules(null, null, "neve"))
    assertThat((w.awaitAlbum(id) { it.count == 2 } as Album.Smart).suggested).isEqualTo(1)
    assertThat(w.album(id).sort).isEqualTo(AlbumSort.OLDEST_FIRST)
    assertThat(choices.exclude(id, listOf(media(1).id))).isEqualTo(EditOutcome.Done)
    assertThat(choices.accept(id, listOf(media(3).id))).isEqualTo(EditOutcome.Done)
    assertThat(w.items(id)).containsExactly(media(2).id, media(3).id)
    assertThat(choices.freeze(id)).isEqualTo(EditOutcome.Done)
    w.setSmart(confident = listOf(media(4).id), suggested = emptyList())
    assertThat((w.awaitAlbum(id) { (it as Album.Smart).frozen } as Album.Smart).count).isEqualTo(2)
    assertThat(choices.unfreeze(id)).isEqualTo(EditOutcome.Done)
    assertThat(w.items(id)).containsExactly(media(3).id, media(4).id)
    val manual = w.create("Manuale")
    assertThat(choices.freeze(manual)).isInstanceOf(EditOutcome.NotAllowed::class.java)
  }

  /** P10 (richiesta di P3): le regole di uno smart si cambiano dall'editor; le esclusioni restano; una regola troppo lunga si dice, senza scrivere niente. */
  @Test
  fun leRegoleDiUnoSmartSiCambianoEUnaRegolaTroppoLungaSiDice() = run {
    val w = world()
    val choices = w.choices ?: return@run
    w.library.put(media(1), media(2), media(3))
    w.setSmart(confident = listOf(media(1).id, media(2).id), suggested = emptyList())
    val id = w.create("Oslo 2026", AlbumKind.SMART, SmartRules(null, null, "neve"))
    assertThat(choices.exclude(id, listOf(media(1).id))).isEqualTo(EditOutcome.Done)
    // Il cambio vale e non cancella le esclusioni (E-AL13).
    assertThat(w.albums.setRules(id, SmartRules(null, null, "mare", sources = setOf(SmartSource.MY_PHONE)))).isEqualTo(EditOutcome.Done)
    assertThat(w.items(id)).containsExactly(media(2).id)
    // Un album manuale non ha regole; uno che non c'e' non si trova.
    val manual = w.create("Manuale")
    assertThat(w.albums.setRules(manual, SmartRules(null, null, "x"))).isInstanceOf(EditOutcome.NotAllowed::class.java)
    assertThat(w.albums.setRules(AlbumId("00000000-0000-4000-8000-000000000777"), SmartRules(null, null, "x"))).isEqualTo(EditOutcome.NotFound)
    // Una descrizione che non sta in una voce (1.024 caratteri): non si scrive a meta' e il caso e' quello delle regole, non del titolo.
    val tooLong = SmartRules(null, null, "a".repeat(2_000))
    assertThat(w.albums.setRules(id, tooLong)).isInstanceOf(EditOutcome.Invalid::class.java)
    assertThat(w.albums.create("Troppo", AlbumKind.SMART, tooLong)).isEqualTo(CreateOutcome.RulesTooLong)
    assertThat(w.items(id)).containsExactly(media(2).id)
  }
}
