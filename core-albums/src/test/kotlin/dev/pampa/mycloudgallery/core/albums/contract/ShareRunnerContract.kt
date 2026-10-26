package dev.pampa.mycloudgallery.core.albums.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LA SUITE DI CONTRATTO di [ShareRunner] (docs/fase7-progetto.md §2.5, §2.6, §5.5; D12, D28-D31, D38; E-SH1, E-SH8, E-SH13, E-SH18).
 * La passa l'esecutore di riferimento (`InMemoryShareRunner`); P4 la fa passare all'orchestratore vero sovrascrivendo [runner] sopra le
 * stesse porte finte ([ReferenceWorld.ports]). Gli invarianti del §5.5 si controllano sul diario e sul mondo finto.
 */
abstract class ShareRunnerContract {

  protected abstract fun world(): ReferenceWorld

  /** Un processo nuovo dell'orchestratore sopra le porte e il diario di [w]. */
  protected open fun runner(w: ReferenceWorld): ShareRunner = w.runner()

  private val base = 1_767_225_600_000L

  private fun media(n: Int, place: MediaPlace, onPhone: Boolean = place == MediaPlace.PHONE_ONLY, hidden: Boolean = false, video: Boolean = false) =
    FakeMedia(
      FakeAlbumLibrary.id(n), base + n * 3_600_000L, place = place, onPhone = onPhone, hidden = hidden,
      kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO, size = 1_000L * n,
    )

  private fun ReferenceWorld.confirm(plan: SharePlan) = ShareConfirmationIssuer.issue(plan, clocks.monotonicMs())

  private suspend fun ShareRunner.ready(request: ShareRequest): SharePlan = (plan(request) as SharePlanOutcome.Ready).plan

  /** Gira finche' tutto e' terminale, facendo passare il tempo (una testa ogni 5 minuti). */
  private suspend fun ReferenceWorld.drain(runner: ShareRunner, rounds: Int = 6) {
    repeat(rounds) {
      runner.resumeAll(1.minutes)
      clocks.advance(6 * 60_000L)
    }
  }

  private fun ReferenceWorld.items(): List<ShareItemRecord> = diary.allItems()

  /** Invariante 6: ogni voce `item:` del mondo comune rimanda a un file che il catalogo vede nell'area comune. */
  private fun ReferenceWorld.assertNoEntryWithoutFile() {
    for (id in shared.albumIds()) {
      val keys = shared.album(id)!!.items.values.map { it.contentKey }
      assertThat(library.attestedNow(keys).keys).containsExactlyElementsIn(keys)
    }
  }

  @Test
  fun ilPianoNonScriveNienteENonChiamaPorte() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.PHONE_ONLY))
    val out = runner(w).plan(ShareRequest.NewAlbum("Prova Oslo", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    assertThat(out).isInstanceOf(SharePlanOutcome.Ready::class.java)
    assertThat(w.transfers.started).isEmpty()
    assertThat(w.publisher.pending.value).isEqualTo(0)
    assertThat(w.diary.allOps()).isEmpty()
    assertThat(w.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
  }

  /** D12: aree degli altri non aggiungibili, nascosti fuori con «Lascia fuori» (Q3), conteggi, spazio richiesto = byte + 1 GiB. */
  @Test
  fun ilPianoConteggiaEdEscludeComeIlProgetto() = runBlocking<Unit> {
    val w = world()
    w.library.put(
      media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.PHONE_ONLY, video = true), media(3, MediaPlace.COMMON),
      media(4, MediaPlace.OTHERS_PRIVATE), media(5, MediaPlace.MY_PRIVATE, hidden = true),
    )
    val ids = (1..5).map { FakeAlbumLibrary.id(it) }
    val plan = runner(w).ready(ShareRequest.NewAlbum("Oslo", ShareLevel.ADD, ids))
    assertThat(plan.items.associate { it.media to it.action }).containsExactly(
      ids[0], ShareItemAction.MOVE_IN, ids[1], ShareItemAction.UPLOAD, ids[2], ShareItemAction.REFERENCE,
    )
    assertThat(plan.excluded).containsExactly(ShareExclusion.OTHERS_AREA, 1, ShareExclusion.HIDDEN_LEFT_OUT, 1)
    assertThat(plan.photos to plan.videos).isEqualTo(2 to 1)
    assertThat(plan.bytes).isEqualTo(1_000L + 2_000L)
    assertThat(plan.requiredBytes).isEqualTo(3_000L + (1L shl 30))
    assertThat(plan.folderLabel).isEqualTo("Album condivisi/Oslo")
    val withHidden = runner(w).ready(ShareRequest.NewAlbum("Oslo", ShareLevel.ADD, ids, HiddenChoice.SHARE_TOO))
    assertThat(withHidden.hiddenIncluded).isEqualTo(1)
    assertThat(withHidden.hash).isNotEqualTo(plan.hash)
  }

  /** C26: senza una conferma valida per **questo** piano non parte niente. */
  @Test
  fun senzaUnaConfermaValidaNonParteNiente() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE))
    val r = runner(w)
    val a = r.ready(ShareRequest.NewAlbum("A", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    val b = r.ready(ShareRequest.NewAlbum("B", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(2))))
    assertThat(r.start(a, w.confirm(b))).isEqualTo(ShareStartOutcome.ConfirmationRejected(ShareConfirmationCheck.WrongPlan))
    val old = w.confirm(a)
    w.clocks.advance(ShareConfirmationRules.MAX_AGE_MS + 1)
    assertThat(r.start(a, old)).isEqualTo(ShareStartOutcome.ConfirmationRejected(ShareConfirmationCheck.Expired))
    assertThat(w.transfers.started).isEmpty()
    assertThat(w.diary.allOps()).isEmpty()
  }

  /** F79 sul finto: spostamento, caricamento, riferimento; stessi `MediaId`; guardiano solo per i miei spostati senza telefono (D28). */
  @Test
  fun laCondivisioneArrivaInFondoConGliInvarianti() = runBlocking<Unit> {
    val w = world()
    w.library.put(
      media(1, MediaPlace.MY_PRIVATE, onPhone = true), media(2, MediaPlace.MY_PRIVATE, onPhone = false), media(3, MediaPlace.PHONE_ONLY),
      media(4, MediaPlace.COMMON), media(5, MediaPlace.MY_PRIVATE, hidden = true),
    )
    val ids = (1..5).map { FakeAlbumLibrary.id(it) }
    val r = runner(w)
    val plan = r.ready(ShareRequest.NewAlbum("Prova Oslo", ShareLevel.ADD, ids))
    val started = r.start(plan, w.confirm(plan)) as ShareStartOutcome.Started
    assertThat(r.start(plan, w.confirm(plan))).isEqualTo(ShareStartOutcome.AlreadyStarted(started.op))
    w.drain(r)
    assertThat(w.items().map { it.state }.toSet()).containsExactly(ShareItemState.DONE)
    assertThat(r.operations.value.single()).isInstanceOf(ShareOpState.Done::class.java)
    // Stessi media, ora nell'area comune; il nascosto lasciato fuori resta dov'era (invariante 9).
    for (n in 1..4) assertThat(w.library[ids[n - 1]]!!.place).isEqualTo(MediaPlace.COMMON)
    assertThat(w.library[ids[4]]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(w.library.all()).hasSize(5)
    // La definizione e i quattro elementi pubblicati; ogni voce ha il suo file nell'area comune (invariante 6).
    val album = w.shared.album(plan.album)!!
    assertThat(album.items.keys).containsExactlyElementsIn(ids.take(4))
    assertThat(album.creator).isEqualTo(AccountId("test"))
    w.assertNoEntryWithoutFile()
    // D31: la cartella d'origine dei due spostati, prima dello spostamento. D28: guardiano solo per il secondo.
    assertThat(w.origins.origins.keys).containsExactly(ids[0], ids[1])
    assertThat(w.safeCopies.requested).containsExactly(ids[1])
    // Invariante 7: ogni trasferimento nasce da una conferma registrata nel diario con la stessa impronta; uno per tipo, una volta.
    assertThat(w.transfers.started.map { it.planHash }.toSet()).containsExactly(plan.hash)
    assertThat(w.transfers.started).hasSize(2)
  }

  /** La morte del processo subito dopo l'avvio di un trasferimento: la ripresa ritrova il biglietto per chiave, nessun doppio spostamento. */
  @Test
  fun laRipresaRitrovaIlTrasferimentoGiaAvviato() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    val plan = runner(w).ready(ShareRequest.NewAlbum("Ripresa", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    w.transfers.crashAfterStart = true
    runner(w).start(plan, w.confirm(plan))
    w.drain(runner(w))
    assertThat(w.transfers.started).hasSize(1)
    assertThat(w.items().single().state).isEqualTo(ShareItemState.DONE)
  }

  /** Morte dopo il diario e prima di chiamare la porta: la conferma non c'e' piu', niente si e' mosso, l'elemento lo dice. */
  @Test
  fun unTrasferimentoMaiPartitoNonPerdeNiente() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    val plan = runner(w).ready(ShareRequest.NewAlbum("Morte", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    w.diary.crashAtSave = 1
    w.diary.crashAfterWrite = true
    assertThrows(SimulatedDeath::class.java) { runBlocking { runner(w).start(plan, w.confirm(plan)) } }
    w.diary.crashAtSave = null
    w.drain(runner(w))
    assertThat(w.transfers.started).isEmpty()
    assertThat(w.items().single().failure).isEqualTo(ShareFailure.NOT_STARTED)
    assertThat(w.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
  }

  /** E-SH18: il file cambiato si ferma con il motivo; nessuna voce per lui; il resto va. */
  @Test
  fun unFileCambiatoSiFermaConIlMotivo() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE))
    val r = runner(w)
    val plan = r.ready(ShareRequest.NewAlbum("Cambiato", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    w.transfers.outcomes[FakeAlbumLibrary.id(2)] = TransferItemOutcome.SOURCE_CHANGED
    r.start(plan, w.confirm(plan))
    w.drain(r)
    val op = r.operations.value.single() as ShareOpState.PartlyDone
    assertThat(op.failures).containsExactly(ShareFailure.SOURCE_CHANGED, 1)
    assertThat(w.shared.album(plan.album)!!.items.keys).containsExactly(FakeAlbumLibrary.id(1))
    w.assertNoEntryWithoutFile()
  }

  /** E-SH8: spazio finito = pausa con il motivo, poi la ripresa arriva in fondo. */
  @Test
  fun lAttesaDelloSpazioEUnaPausaNonUnErrore() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    val r = runner(w)
    val plan = r.ready(ShareRequest.NewAlbum("Spazio", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1))))
    w.transfers.waiting = ShareWait.NO_SPACE
    r.start(plan, w.confirm(plan))
    r.resumeAll(1.minutes)
    assertThat((r.operations.value.single() as ShareOpState.Paused).reason).isEqualTo(ShareWait.NO_SPACE)
    w.transfers.waiting = null
    w.drain(r)
    assertThat(r.operations.value.single()).isInstanceOf(ShareOpState.Done::class.java)
  }

  /** E-SH13: il livello cambia a meta': la voce non si pubblica, il file resta nell'area comune, l'elemento dice perche'. */
  @Test
  fun ilLivelloCambiatoAMetaNonPubblicaLaVoce() = runBlocking<Unit> {
    val w = world()
    val album = w.albumBy(AccountId("test2"))
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    w.transfers.autoComplete = false
    val r = runner(w)
    val plan = r.ready(ShareRequest.AddToShared(album, listOf(FakeAlbumLibrary.id(1))))
    r.start(plan, w.confirm(plan))
    w.shared.apply(album, AccountId("test2"), listOf(SharedAlbumChange.Level(ShareLevel.VIEW)))
    w.transfers.completeAll()
    w.drain(r)
    assertThat(w.items().single().failure).isEqualTo(ShareFailure.NOT_ALLOWED)
    assertThat(w.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.COMMON)
    assertThat(w.shared.album(album)!!.items).isEmpty()
  }

  /** D31: "Riporta" porta i miei media alla cartella d'origine e toglie la voce; i media degli altri no. */
  @Test
  fun riportaSoloIMieiVersoLaCartellaDOrigine() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE))
    val r = runner(w)
    val share = r.ready(ShareRequest.NewAlbum("Riporta", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    r.start(share, w.confirm(share))
    w.drain(r)
    val origin = w.origins.origins.getValue(FakeAlbumLibrary.id(1))
    w.library.update(FakeAlbumLibrary.id(2)) { it.copy(mine = false) }
    val back = r.ready(ShareRequest.BringBack(share.album, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    assertThat(back.items.map { it.media }).containsExactly(FakeAlbumLibrary.id(1))
    assertThat(back.excluded).containsExactly(ShareExclusion.NOT_MINE, 1)
    r.start(back, w.confirm(back))
    w.drain(r)
    val m = w.library[FakeAlbumLibrary.id(1)]!!
    assertThat(m.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertThat(m.path!!.substringBeforeLast('/')).isEqualTo(origin.path.value)
    assertThat(w.shared.album(share.album)!!.items.keys).containsExactly(FakeAlbumLibrary.id(2))
  }

  /** D30: fuori dalle share di prova "Arriva dopo le prove"; D38: un cancello chiuso ferma il piano. */
  @Test
  fun laRecinzioneEICancelliFermanoIlPiano() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    w.family.common = "famiglia"
    val req = ShareRequest.NewAlbum("Vere", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1)))
    assertThat(runner(w).plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.TEST_SHARES_ONLY))
    w.family.common = "test-comune"
    w.gate.blocks["test-comune"] = AlbumWriteBlock.FROZEN
    assertThat(runner(w).plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.WRITES_BLOCKED))
    w.gate.blocks.clear()
    assertThat(runner(w).plan(ShareRequest.NewAlbum(" ", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1)))))
      .isEqualTo(SharePlanOutcome.InvalidTitle(TitleProblem.EMPTY))
    assertThat(w.transfers.started).isEmpty()
  }

  /** §4.3, E-S11: un album condiviso di un altro, anche con voci di media privati, non fa partire niente alla ripresa. */
  @Test
  fun iDatiRemotiNonFannoPartireNiente() = runBlocking<Unit> {
    val w = world()
    val album = w.albumBy(AccountId("test2"))
    w.library.put(media(1, MediaPlace.MY_PRIVATE))
    val f = w.library[FakeAlbumLibrary.id(1)]!!
    w.shared.apply(
      album, AccountId("test2"),
      listOf(SharedAlbumChange.Item(f.id, null, f.contentKey, dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse(f.path!!), 1_000, AccountId("test2"))),
    )
    repeat(3) { runner(w).resumeAll(1.minutes) }
    assertThat(w.transfers.started).isEmpty()
    assertThat(w.safeCopies.requested).isEmpty()
    assertThat(w.library[FakeAlbumLibrary.id(1)]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    // L'elemento c'e' nel mondo (la voce e' permessa), ma rimanda a un file privato: per me non e' attestato e non si mostra (D9).
    assertThat(w.library.attestedNow(listOf(f.contentKey))).isEmpty()
    assertThat(w.sharedAlbums.visibleItems(album)).isEmpty()
  }

  /** Il piano cambiato sul catalogo dopo il foglio non parte: serve un foglio nuovo. */
  @Test
  fun unPianoCambiatoDopoIlFoglioNonParte() = runBlocking<Unit> {
    val w = world()
    w.library.put(media(1, MediaPlace.MY_PRIVATE), media(2, MediaPlace.MY_PRIVATE))
    val r = runner(w)
    val plan = r.ready(ShareRequest.NewAlbum("Cambia", ShareLevel.ADD, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2))))
    w.library.hide(FakeAlbumLibrary.id(2))
    assertThat(r.start(plan, w.confirm(plan))).isEqualTo(ShareStartOutcome.PlanChanged)
    assertThat(w.diary.allOps()).isEmpty()
  }
}

