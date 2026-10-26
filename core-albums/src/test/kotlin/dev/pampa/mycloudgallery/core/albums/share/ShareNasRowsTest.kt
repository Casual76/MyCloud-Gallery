package dev.pampa.mycloudgallery.core.albums.share

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * LE RIGHE DEL §2.5 E DEL §2.6 CON IL MOTORE VERO (P4, riga del §4.4): ogni riga e' un test, **sul NAS finto e sul simulatore SMB con le vere
 * `SmbNasFileOps`** ([ShareNasTarget]), con il pubblicatore e la replica veri di P2 e un familiare che legge. Gli invarianti del §5.5 si
 * controllano a ogni giro (a meta') e alla fine. Edge case: E-SH1, E-SH2, E-SH3, E-SH4, E-SH8, E-SH11, E-SH12, E-SH18, E-SH19; D12, D28,
 * D29, D30, D31, D39; Q5 con i file.
 */
class ShareNasRowsTest {

  private fun forBoth(block: suspend (ShareBench) -> Unit) = runBlocking {
    for (factory in listOf<(dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink) -> ShareNasTarget>(::FakeNasTarget, ::SmbNasTarget)) {
      ShareBench(factory).use { bench -> block(bench) }
    }
  }

  private val ShareBench.label: String get() = target.name

  private fun commonFiles(bench: ShareBench, folder: String): List<String> =
    bench.target.files(ShareNasTarget.COMMON).filter { it.substringBeforeLast('/', "").equals(folder, ignoreCase = true) }

  private fun trashed(bench: ShareBench, share: String): List<String> =
    bench.target.files(share).filter { it.lowercase(Locale.ROOT).startsWith(".mycloudgallery/trash/") && "/files/" in it.lowercase(Locale.ROOT) }

  /** Il catalogo dell'altro familiare vede l'area comune (la sua scansione): gli stessi media, con le stesse chiavi di contenuto (F4-D7). */
  private suspend fun ShareBench.otherScans() {
    for (m in library.all().filter { it.place == MediaPlace.COMMON }) {
      other.library.put(m.copy(mine = false))
    }
    // Una scansione con novita' nell'area comune: la replica ricalcola le attestazioni (D9).
    other.replica.recompute()
  }

  private suspend fun ShareBench.drainChecked(hiddenLeftOut: Set<dev.pampa.mycloudgallery.core.model.MediaId> = emptySet(), rounds: Int = 10) {
    repeat(rounds) { round ->
      round()
      assertWithMessage("$label, giro $round").that(violations(final = false, hiddenLeftOut = hiddenLeftOut)).isEmpty()
    }
    world.settle(2)
  }

  // --- §2.5: piano, conferma, diario, trasferimenti, completamento, voce, al sicuro (F79) ---------------------------------------------------

  @Test
  fun f79LaCondivisioneCompletaDallAreaPrivataDalTelefonoEDallAreaComune() = forBoth { b ->
    val moved = listOf(b.privateMedia(1, onPhone = true), b.privateMedia(2), b.privateMedia(3, folder = "Viaggi/Oslo"), b.privateMedia(4, video = true))
    val uploaded = listOf(b.phoneMedia(5), b.phoneMedia(6))
    val reference = b.commonMedia(7)
    val hidden = b.privateMedia(8, hidden = true)
    val others = b.othersMedia(9)
    val all = moved + uploaded + reference + hidden + others
    // 1. Il piano: niente scritto, nessuna porta.
    val plan = b.plan(ShareRequest.NewAlbum("Prova Oslo", ShareLevel.ADD, all))
    assertWithMessage(b.label).that(plan.items.associate { it.media to it.action }).containsExactlyEntriesIn(
      moved.associateWith { ShareItemAction.MOVE_IN } + uploaded.associateWith { ShareItemAction.UPLOAD } + mapOf(reference to ShareItemAction.REFERENCE),
    )
    assertWithMessage(b.label).that(plan.excluded).containsExactly(ShareExclusion.HIDDEN_LEFT_OUT, 1, ShareExclusion.OTHERS_AREA, 1)
    assertWithMessage(b.label).that(plan.photos to plan.videos).isEqualTo(6 to 1)
    assertWithMessage(b.label).that(plan.requiredBytes).isEqualTo(plan.bytes + (1L shl 30))
    assertWithMessage(b.label).that(plan.folderLabel).isEqualTo("Album condivisi/Prova Oslo")
    assertWithMessage(b.label).that(b.diary.allOps()).isEmpty()
    assertWithMessage(b.label).that(b.target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet())).isEmpty()
    // 2-3. Conferma e diario prima di ogni azione; 4-7 in background.
    assertWithMessage(b.label).that(b.start(plan)).isInstanceOf(ShareStartOutcome.Started::class.java)
    b.drainChecked(hiddenLeftOut = setOf(hidden))
    assertWithMessage(b.label).that(b.items().map { it.state }.toSet()).containsExactly(ShareItemState.DONE)
    assertWithMessage(b.label).that(b.orchestrator.operations.value.single()).isInstanceOf(ShareOpState.Done::class.java)
    // Dal "PC": 6 file nella cartella dell'album con i byte giusti; il riferimento al suo posto; 4 sorgenti nel cestino privato.
    val inFolder = commonFiles(b, "Album condivisi/Prova Oslo")
    assertWithMessage(b.label).that(inFolder).hasSize(6)
    assertWithMessage(b.label).that(inFolder.map { b.target.bytes(ShareNasTarget.COMMON, it)!!.toList() })
      .containsExactlyElementsIn((moved + uploaded).map { b.originals[it]!!.toList() })
    assertWithMessage(b.label).that(trashed(b, ShareNasTarget.PRIVATE)).hasSize(4)
    assertWithMessage(b.label).that(b.library[reference]!!.path).isEqualTo("Famiglia/c_7.jpg")
    // E-SH3: gli stessi media (stessi MediaId) ora nell'area comune; il nascosto dov'era; nessun media in piu' o in meno.
    for (id in moved + uploaded + reference) assertWithMessage(b.label).that(b.library[id]!!.place).isEqualTo(MediaPlace.COMMON)
    assertWithMessage(b.label).that(b.library[hidden]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertWithMessage(b.label).that(b.library.all()).hasSize(all.size)
    // 6. Le voci, e il familiare vede l'album con i 7 elementi quando il suo catalogo vede i file.
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).containsExactlyElementsIn(moved + uploaded + reference)
    b.otherScans()
    b.world.settle(1)
    assertWithMessage(b.label).that(b.seenByOther(plan.album)).containsExactlyElementsIn(moved + uploaded + reference)
    // D31: la cartella d'origine dei 4 spostati, prima dello spostamento; D28: guardiano solo per i miei spostati senza telefono.
    assertWithMessage(b.label).that(b.origins.origins.keys).containsExactlyElementsIn(moved)
    assertWithMessage(b.label).that(b.origins.origins[moved[2]]!!.path.value).isEqualTo("Viaggi/Oslo")
    assertWithMessage(b.label).that(b.safeCopies.requested).containsExactly(moved[1], moved[2], moved[3])
    assertWithMessage(b.label).that(b.violations(final = true, hiddenLeftOut = setOf(hidden), transientOnly = true)).isEmpty()
  }

  /** Nascosti (Q3, E-SH9): con «Condividi anche i nascosti» il nascosto si sposta ed e' contato nel piano; con «Lascia fuori» no. */
  @Test
  fun eSh9INascostiCondivisiConLaScelta() = forBoth { b ->
    val shown = b.privateMedia(1)
    val hidden = b.privateMedia(2, hidden = true)
    val plan = b.plan(ShareRequest.NewAlbum("Con i nascosti", ShareLevel.ADD, listOf(shown, hidden), HiddenChoice.SHARE_TOO))
    assertWithMessage(b.label).that(plan.hiddenIncluded).isEqualTo(1)
    assertWithMessage(b.label).that(plan.hiddenLeftOut).isEqualTo(0)
    b.start(plan)
    b.drainChecked()
    assertWithMessage(b.label).that(b.library[hidden]!!.place).isEqualTo(MediaPlace.COMMON)
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).containsExactly(shown, hidden)
  }

  /** E-SH4: nomi gia' occupati nella cartella dell'album (un file messo dal PC, due file con lo stesso nome): nome alternativo, niente sovrascritto. */
  @Test
  fun eSh4NomiOccupatiNessunaSovrascrittura() = forBoth { b ->
    val pc = b.commonMedia(10, folder = "Album condivisi/Gita", name = "x.jpg")
    val a = b.privateMedia(1, folder = "A", name = "x.jpg")
    val c = b.privateMedia(2, folder = "B", name = "x.jpg")
    val plan = b.plan(ShareRequest.NewAlbum("Gita", ShareLevel.ADD, listOf(a, c)))
    b.start(plan)
    b.drainChecked()
    val names = commonFiles(b, "Album condivisi/Gita").map { it.substringAfterLast('/') }
    assertWithMessage(b.label).that(names).containsAtLeast("x.jpg", "x (2).jpg", "x (3).jpg")
    assertWithMessage(b.label).that(b.target.bytes(ShareNasTarget.COMMON, "Album condivisi/Gita/x.jpg")).isEqualTo(b.originals[pc])
    val paths = b.publishedItems(plan.album).mapValues { it.value.path.value }
    assertWithMessage(b.label).that(paths.values.toSet()).containsExactly("Album condivisi/Gita/x (2).jpg", "Album condivisi/Gita/x (3).jpg")
    assertWithMessage(b.label).that(b.violations(final = true, transientOnly = true)).isEmpty()
  }

  /** E-SH22: un titolo con caratteri vietati e un nome riservato: la cartella ha un nome corretto, il titolo resta quello scelto. */
  @Test
  fun eSh22CartellaConUnNomeCorretto() = forBoth { b ->
    val m = b.privateMedia(1)
    val plan = b.plan(ShareRequest.NewAlbum("CON: gita?.", ShareLevel.ADD, listOf(m)))
    assertWithMessage(b.label).that(plan.title).isEqualTo("CON: gita?.")
    assertWithMessage(b.label).that(plan.folderLabel).isEqualTo("Album condivisi/CON_ gita_")
    b.start(plan)
    b.drainChecked()
    assertWithMessage(b.label).that(b.library[m]!!.path!!.substringBeforeLast('/')).isEqualTo("Album condivisi/CON_ gita_")
    // Un secondo album con lo stesso titolo ha la sua cartella.
    val n = b.privateMedia(2)
    val second = b.plan(ShareRequest.NewAlbum("CON: gita?.", ShareLevel.ADD, listOf(n)))
    assertWithMessage(b.label).that(second.folderLabel).isEqualTo("Album condivisi/CON_ gita_ (2)")
  }

  // --- Errori e pause (§2.5 "Errori") --------------------------------------------------------------------------------------------------

  /** E-SH8, F81: lo spazio non basta (dimensione + 1 GiB): pausa prima del primo file, nessuno spostamento a meta'; tolto il tetto, si finisce. */
  @Test
  fun eSh8SpazioFinitoPausaPrimaDelPrimoFile() = forBoth { b ->
    val m = listOf(b.privateMedia(1), b.privateMedia(2))
    b.target.setFreeBytes(1L shl 20)
    val plan = b.plan(ShareRequest.NewAlbum("Spazio", ShareLevel.ADD, m))
    b.start(plan)
    b.orchestrator.resumeAll(kotlin.time.Duration.parse("1h"))
    assertWithMessage(b.label).that((b.orchestrator.operations.value.single() as ShareOpState.Paused).reason).isEqualTo(ShareWait.NO_SPACE)
    assertWithMessage(b.label).that(b.items().map { it.state }.toSet()).containsExactly(ShareItemState.PLANNED)
    assertWithMessage(b.label).that(b.target.files(ShareNasTarget.COMMON).filterNot { it.startsWith(".mycloudgallery") }).isEmpty()
    assertWithMessage(b.label).that(b.violations(final = false)).isEmpty()
    b.target.setFreeBytes(null)
    b.drainChecked()
    assertWithMessage(b.label).that(b.orchestrator.operations.value.single()).isInstanceOf(ShareOpState.Done::class.java)
  }

  /** E-SH18 (E-D13): il file sorgente cambiato dal PC prima della copia: l'elemento si ferma con il motivo, entrambe le copie restano, nessuna voce. */
  @Test
  fun eSh18FileCambiatoDalPc() = forBoth { b ->
    val ok = b.privateMedia(1)
    val changed = b.privateMedia(2)
    val path = b.library[changed]!!.path!!
    // Il PC tiene aperto il file: la copia aspetta (E-N13); intanto lo cambia e lo chiude.
    b.target.lock(ShareNasTarget.PRIVATE, path)
    val plan = b.plan(ShareRequest.NewAlbum("Cambiato", ShareLevel.ADD, listOf(ok, changed)))
    b.start(plan)
    b.modifyFromPc(changed, ByteArray(300) { 7 })
    b.target.unlock(ShareNasTarget.PRIVATE, path)
    b.drainChecked(rounds = 14)
    val failed = b.items().single { it.media == changed }
    assertWithMessage(b.label).that(failed.failure).isEqualTo(ShareFailure.SOURCE_CHANGED)
    assertWithMessage(b.label).that(b.library[changed]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertWithMessage(b.label).that(b.target.bytes(ShareNasTarget.PRIVATE, path)!!.toList()).isEqualTo(ByteArray(300) { 7 }.toList())
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).containsExactly(ok)
    assertWithMessage(b.label).that((b.orchestrator.operations.value.single() as ShareOpState.PartlyDone).failures).containsExactly(ShareFailure.SOURCE_CHANGED, 1)
    assertWithMessage(b.label).that(b.violations(final = true)).isEmpty()
  }

  /** E-SH19 (D38): area congelata a meta' (cancello delle operazioni) e poi le scritture degli album bloccate: pausa con il motivo, ripresa. */
  @Test
  fun eSh19CongelamentoAMetaPausaERipresa() = forBoth { b ->
    val m = listOf(b.privateMedia(1), b.privateMedia(2))
    b.target.gates[ShareNasTarget.PRIVATE] = GateStatus.FROZEN
    val plan = b.plan(ShareRequest.NewAlbum("Congelata", ShareLevel.ADD, m))
    b.start(plan)
    b.orchestrator.resumeAll(kotlin.time.Duration.parse("1h"))
    assertWithMessage(b.label).that((b.orchestrator.operations.value.single() as ShareOpState.Paused).reason).isEqualTo(ShareWait.WRITES_BLOCKED)
    assertWithMessage(b.label).that(b.violations(final = false)).isEmpty()
    b.target.gates.clear()
    // I file arrivano; ora si chiudono le scritture degli album: le voci aspettano con il motivo.
    b.me.gate.blocks[ShareNasTarget.COMMON] = AlbumWriteBlock.FROZEN
    repeat(4) { b.round() }
    assertWithMessage(b.label).that(m.map { b.library[it]!!.place }.toSet()).containsExactly(MediaPlace.COMMON)
    assertWithMessage(b.label).that((b.orchestrator.operations.value.single() as ShareOpState.Paused).reason).isEqualTo(ShareWait.WRITES_BLOCKED)
    assertWithMessage(b.label).that(b.publishedItems(plan.album)).isEmpty()
    b.me.gate.blocks.clear()
    b.drainChecked()
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).containsExactlyElementsIn(m)
    assertWithMessage(b.label).that(b.violations(final = true, transientOnly = true)).isEmpty()
  }

  /** E-SH19 al piano e D30: con l'area congelata, in sola lettura o fuori dalle share di prova la conferma non si offre. */
  @Test
  fun eSh19ED30IlPianoNonSiOffre() = forBoth { b ->
    val m = b.privateMedia(1)
    val req = ShareRequest.NewAlbum("No", ShareLevel.ADD, listOf(m))
    b.me.gate.blocks[ShareNasTarget.PRIVATE] = AlbumWriteBlock.FROZEN
    assertWithMessage(b.label).that(b.orchestrator.plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.WRITES_BLOCKED))
    b.me.gate.blocks[ShareNasTarget.PRIVATE] = AlbumWriteBlock.READ_ONLY
    assertWithMessage(b.label).that(b.orchestrator.plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.READ_ONLY))
    b.me.gate.blocks[ShareNasTarget.COMMON] = AlbumWriteBlock.FENCE
    b.me.gate.blocks.remove(ShareNasTarget.PRIVATE)
    assertWithMessage(b.label).that(b.orchestrator.plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.TEST_SHARES_ONLY))
    b.me.gate.blocks.clear()
    b.me.albumFamily.common = "famiglia"
    assertWithMessage(b.label).that(b.orchestrator.plan(req)).isEqualTo(SharePlanOutcome.Blocked(ShareBlock.TEST_SHARES_ONLY))
    assertWithMessage(b.label).that(b.mover.keys).isEmpty()
    assertWithMessage(b.label).that(b.diary.allOps()).isEmpty()
  }

  /**
   * E-SH12: lo stesso media aggiunto insieme da un altro dispositivo: quando il trasferimento sta per partire, il catalogo vede gia' il file
   * nell'area comune: nessuna copia, un file solo, la voce cita quello.
   */
  @Test
  fun eSh12IlFileGiaNellAreaComuneNonSiCopia() = forBoth { b ->
    val m = b.privateMedia(1)
    val plan = b.plan(ShareRequest.NewAlbum("Insieme", ShareLevel.ADD, listOf(m)))
    // La prima chiamata della porta non riesce (rete): l'elemento resta da trasferire.
    var refused = false
    b.wrapMover = { inner -> dev.pampa.mycloudgallery.core.albums.contract.AlbumMover { k, i, d, l, c -> if (!refused) { refused = true; TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE) } else inner.move(k, i, d, l, c) } }
    b.rewire()
    b.start(plan)
    // Intanto un altro mio dispositivo lo sposta nell'area comune (il catalogo lo vede alla scansione; la sorgente esce dall'area privata).
    val bytes = b.originals[m]!!
    val oldPath = b.library[m]!!.path!!
    b.target.put(ShareNasTarget.COMMON, "Famiglia/img_1.jpg", bytes)
    b.target.delete(ShareNasTarget.PRIVATE, oldPath)
    b.library.move(m, MediaPlace.COMMON, "Famiglia/img_1.jpg")
    b.drainChecked()
    assertWithMessage(b.label).that(b.items().single().state).isEqualTo(ShareItemState.DONE)
    assertWithMessage(b.label).that(b.items().single().ticket).isNull()
    assertWithMessage(b.label).that(b.mover.keys).isEmpty()
    assertWithMessage(b.label).that(b.publishedItems(plan.album).getValue(m).path.value).isEqualTo("Famiglia/img_1.jpg")
    assertWithMessage(b.label).that(b.safeCopies.requested).isEmpty()
  }

  // --- §2.6: Riporta, Smetti/Elimina (Q5), adozione (D39) ---------------------------------------------------------------------------------

  /** D31, §2.6: "Riporta" solo i miei media verso `shared_origin`; la copia comune nel cestino comune; lapide nell'album attivo; stesso media. */
  @Test
  fun riportaSoloIMieiVersoLaCartellaDOrigine() = forBoth { b ->
    val a = b.privateMedia(1, folder = "Viaggi/Oslo")
    val c = b.privateMedia(2, folder = "Viaggi/Oslo")
    val theirs = b.commonMedia(3, mine = false)
    val plan = b.plan(ShareRequest.NewAlbum("Riporta", ShareLevel.ADD, listOf(a, c, theirs)))
    b.start(plan)
    b.drainChecked()
    val back = b.plan(ShareRequest.BringBack(plan.album, listOf(a, theirs)))
    assertWithMessage(b.label).that(back.items.map { it.media }).containsExactly(a)
    assertWithMessage(b.label).that(back.excluded).containsExactly(ShareExclusion.NOT_MINE, 1)
    b.start(back)
    b.drainChecked()
    val m = b.library[a]!!
    assertWithMessage(b.label).that(m.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertWithMessage(b.label).that(m.path).isEqualTo("Viaggi/Oslo/img_1.jpg")
    assertWithMessage(b.label).that(b.target.bytes(ShareNasTarget.PRIVATE, "Viaggi/Oslo/img_1.jpg")).isEqualTo(b.originals[a])
    assertWithMessage(b.label).that(trashed(b, ShareNasTarget.COMMON)).hasSize(1)
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).containsExactly(c, theirs)
    b.otherScans()
    b.world.settle(1)
    assertWithMessage(b.label).that(b.seenByOther(plan.album)).containsExactly(c, theirs)
    assertWithMessage(b.label).that(b.violations(final = true, transientOnly = true)).isEmpty()
  }

  /**
   * Q5 con i file: "Smetti di condividere" con `KEEP_COPY` e "Elimina" con `REMOVE` non muovono **nessun** file (nessuna operazione, nessun
   * caricamento); le copie personali nascono per chi le deve avere. "Riporta" dopo la chiusura e' a parte: riporta, senza lapide.
   */
  @Test
  fun q5ChiudereNonMuoveMaiFile() = forBoth { b ->
    val kept = b.plan(ShareRequest.NewAlbum("Tenuto", ShareLevel.ADD, listOf(b.privateMedia(1), b.privateMedia(2))))
    b.start(kept)
    val removed = b.plan(ShareRequest.NewAlbum("Tolto", ShareLevel.ADD, listOf(b.privateMedia(3))))
    b.start(removed)
    b.drainChecked()
    b.otherScans()
    b.world.settle(1)
    val files = (b.target.files(ShareNasTarget.PRIVATE) + b.target.files(ShareNasTarget.COMMON)).sorted()
    val ops = b.target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet()).size
    val uploads = b.uploader.started.size
    assertWithMessage(b.label).that(b.me.shared.close(kept.album, CloseMode.STOP, OthersAfterClose.KEEP_COPY)).isEqualTo(CloseOutcome.Closed)
    assertWithMessage(b.label).that(b.me.shared.close(removed.album, CloseMode.DELETE, OthersAfterClose.REMOVE)).isEqualTo(CloseOutcome.Closed)
    b.drain()
    val otherPersonal = InMemoryPersonalStore(b.other.deviceId, nasNow = { b.world.clocks.nas })
    val myPersonal = InMemoryPersonalStore(b.me.deviceId, nasNow = { b.world.clocks.nas })
    assertWithMessage(b.label).that(ClosedAlbumCopies({ b.other.shared.keptCopies() }, PersonalKeptCopyStore(otherPersonal)).sync()).isGreaterThan(0)
    assertWithMessage(b.label).that(ClosedAlbumCopies({ b.me.shared.keptCopies() }, PersonalKeptCopyStore(myPersonal)).sync()).isGreaterThan(0)
    repeat(3) { b.round() }
    assertWithMessage(b.label).that((b.target.files(ShareNasTarget.PRIVATE) + b.target.files(ShareNasTarget.COMMON)).sorted()).isEqualTo(files)
    assertWithMessage(b.label).that(b.target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet())).hasSize(ops)
    assertWithMessage(b.label).that(b.uploader.started).hasSize(uploads)
    // Le copie: il familiare tiene "Tenuto" (KEEP_COPY), non "Tolto" (REMOVE); il creatore tiene il suo dopo "Smetti", non dopo "Elimina".
    val otherCopies = PersonalAlbumsView(otherPersonal).titles()
    assertWithMessage(b.label).that(otherCopies).containsExactly("Tenuto")
    assertWithMessage(b.label).that(PersonalAlbumsView(myPersonal).titles()).containsExactly("Tenuto")
    assertWithMessage(b.label).that(b.me.shared.visible.value.map { it.id }).doesNotContain(kept.album)
    // "Riporta" dopo la chiusura: il media torna, nessuna lapide (l'album e' chiuso).
    val first = kept.items.first().media
    val back = b.plan(ShareRequest.BringBack(kept.album, listOf(first)))
    b.start(back)
    b.drainChecked()
    assertWithMessage(b.label).that(b.library[first]!!.place).isEqualTo(MediaPlace.MY_PRIVATE)
    assertWithMessage(b.label).that(b.items().filter { it.op == b.diary.allOps().last().id }.map { it.stageSeq }).containsExactly(null)
    assertWithMessage(b.label).that(b.violations(final = true, transientOnly = true)).isEmpty()
  }

  /** D39: un file aggiunto dal PC nella cartella dell'album non entra da solo; l'album lo propone; l'adozione e' un piano senza spostamenti. */
  @Test
  fun d39IFileAggiuntiDalPcSiAdottanoSenzaSpostarli() = forBoth { b ->
    val plan = b.plan(ShareRequest.NewAlbum("Gita", ShareLevel.ADD, listOf(b.privateMedia(1))))
    b.start(plan)
    b.drainChecked()
    // Dal PC: un file nuovo nella cartella dell'album; la scansione del catalogo lo vede.
    val pc = b.commonMedia(5, folder = "Album condivisi/Gita")
    val adoptions = FolderAdoptions(b.ports()) { ids -> b.library.visibleNow(ids) }
    assertWithMessage(b.label).that(adoptions.pending(plan.album)).containsExactly(pc)
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).doesNotContain(pc)
    val ops = b.target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet()).size
    val adopt = b.plan(ShareRequest.FolderAdoption(plan.album, listOf(pc)))
    assertWithMessage(b.label).that(adopt.items.single().action).isEqualTo(ShareItemAction.ADOPT)
    b.start(adopt)
    b.drainChecked()
    assertWithMessage(b.label).that(b.target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet())).hasSize(ops)
    assertWithMessage(b.label).that(b.publishedItems(plan.album).keys).contains(pc)
    assertWithMessage(b.label).that(adoptions.pending(plan.album)).isEmpty()
  }

  /** §2.6 "Lasciare l'album" ed "Ereditare": nessuna azione sui file (nessuna operazione, nessun caricamento) e nessun elemento cambia posto. */
  @Test
  fun lasciareENessunaAzioneSuiFile() = forBoth { b ->
    val plan = b.plan(ShareRequest.NewAlbum("Di tutti", ShareLevel.ADD, listOf(b.privateMedia(1))))
    b.start(plan)
    b.drainChecked()
    b.otherScans()
    b.world.settle(1)
    val files = (b.target.files(ShareNasTarget.PRIVATE) + b.target.files(ShareNasTarget.COMMON)).sorted()
    assertWithMessage(b.label).that(b.other.shared.leave(plan.album)).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.EditOutcome.Done)
    assertWithMessage(b.label).that(b.other.shared.inherit(plan.album)).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome.NotAdmin)
    repeat(3) { b.round() }
    b.world.settle(1)
    assertWithMessage(b.label).that((b.target.files(ShareNasTarget.PRIVATE) + b.target.files(ShareNasTarget.COMMON)).sorted()).isEqualTo(files)
    assertWithMessage(b.label).that(b.me.shared.visible.value.single().state).isEqualTo(SharedState.ACTIVE)
  }
}

/** I titoli degli album personali di uno store (per le copie di Q5). */
private class PersonalAlbumsView(private val store: InMemoryPersonalStore) {
  fun titles(): List<String> = dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums(store).albums().map { it.title }
}
