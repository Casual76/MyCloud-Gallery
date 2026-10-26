package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il verificatore degli invarianti (docs/fase4-progetto.md §5.5) ha i denti: per ogni invariante, un mondo costruito a mano che lo viola e
 * uno che lo rispetta. Un banco che dice "verde" vale solo se qui il rosso e' davvero rosso.
 */
class ChaosVerifierTest {

  private val device = InMemoryTarget.DEVICE
  private val shares = listOf("test", "test-comune")

  private fun bytes(id: Int, size: Int = 400): ByteArray = OriginalBytes.make(kotlin.random.Random(id), id, size)

  private class Setup(val world: InMemoryWorld, val ledger: Ledger)

  private fun world(vararg originals: Pair<Int, Pair<String, String>>): Setup {
    val world = InMemoryWorld(listOf("test", "test-comune"), InMemoryTarget.DEVICE, "test")
    val ledger = Ledger()
    for ((id, where) in originals) {
      val bytes = OriginalBytes.make(kotlin.random.Random(id), id, 400)
      ledger.add(Original.of(id, bytes))
      world.nas.putFile(where.first, where.second, NasContent.of(bytes))
    }
    return Setup(world, ledger)
  }

  private suspend fun scan(w: Setup, cache: Boolean = false): Snapshot =
    NasScanner(w.world.reader, ScanScope.wholeShares(w.world.shares), setOf(device), useCache = cache).scan()

  private fun context(w: Setup, diary: DiaryView? = null, survives: (Original) -> Boolean = { true }) =
    VerifyContext(w.ledger, diary, device, survives, writesTrashRecords = false, strictAppArea = true)

  private fun List<Violation>.invariants(): Set<Invariant> = map { it.invariant }.toSet()

  // --- Il mondo a posto passa --------------------------------------------------------------------------------------------------------

  @Test
  fun unMondoACostoPassaTuttiGliInvarianti(): Unit = runBlocking {
    val w = world(0 to ("test" to "Foto/a.jpg"), 1 to ("test-comune" to "b.jpg"))
    val expectation = Expectation(
      visible = mapOf(
        w.ledger.byId(0).sha to ExpectedVisible(Place("test", "Foto/a.jpg"), false),
        w.ledger.byId(1).sha to ExpectedVisible(Place("test-comune", "b.jpg"), false),
      ),
      trashCopies = emptyMap(),
      gone = emptySet(),
      dirs = setOf(Place("test", "Foto")),
      slots = emptyMap(),
    )
    val snapshot = scan(w)
    assertThat(Verifier.quiescent(snapshot, context(w), expectation)).isEmpty()
    assertThat(Verifier.midState(snapshot, context(w))).isEmpty()
    assertThat(snapshot.filesHashed).isEqualTo(2)
  }

  // --- 1. Nessun file a meta' --------------------------------------------------------------------------------------------------------

  @Test
  fun unFileTroncatoEUnFileAMeta_ilNAS_haUnHashChePerOgniOriginaleNonEsiste(): Unit = runBlocking {
    val w = world(0 to ("test" to "a.jpg"))
    val whole = bytes(1)
    w.ledger.add(Original.of(1, whole))
    w.world.nas.putFile("test", "b.jpg", NasContent.of(whole.copyOf(whole.size / 2))) // la copia si e' fermata a meta'
    val found = Verifier.quiescent(scan(w), context(w), null)
    assertThat(found.invariants()).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
    assertThat(Verifier.midState(scan(w), context(w)).invariants()).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
  }

  @Test
  fun ancheUnSoloBitDiversoEUnFileACostoSbagliato_hashAlCento(): Unit = runBlocking {
    val w = world()
    val whole = bytes(0)
    w.ledger.add(Original.of(0, whole))
    val flipped = whole.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } // stessa dimensione, un byte diverso
    w.world.nas.putFile("test", "a.jpg", NasContent.of(flipped))
    assertThat(Verifier.quiescent(scan(w), context(w), null).invariants()).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
  }

  // --- 2. Nessun doppione ------------------------------------------------------------------------------------------------------------

  @Test
  fun unOriginaleVisibileDueVolteEUnDoppione_ESoloUnoSpostamentoInCorsoLoGiustifica(): Unit = runBlocking {
    val w = world(0 to ("test" to "a.jpg"))
    w.world.nas.putFile("test-comune", "Copia/a.jpg", NasContent.of(w.ledger.byId(0).bytes!!))
    val snapshot = scan(w)
    assertThat(Verifier.quiescent(snapshot, context(w), null).invariants()).contains(Invariant.DUPLICATE)
    assertThat(Verifier.midState(snapshot, context(w)).invariants()).contains(Invariant.DUPLICATE)

    // Con un elemento di spostamento tra share pubblicato ma con la sorgente non ancora nel cestino, a meta' va bene; a operazioni finite no.
    val mid = FakeDiary(items = listOf(crossShareItem(OpItemState.PUBLISHED, w.ledger.byId(0).sha)))
    assertThat(Verifier.midState(snapshot, context(w, mid))).isEmpty()
    assertThat(Verifier.quiescent(snapshot, context(w, mid), null).invariants()).contains(Invariant.DUPLICATE)
    // Ma se l'elemento non e' arrivato alla verifica, due copie non si spiegano.
    val early = FakeDiary(items = listOf(crossShareItem(OpItemState.COPIED, w.ledger.byId(0).sha)))
    assertThat(Verifier.midState(snapshot, context(w, early)).invariants()).contains(Invariant.DUPLICATE)
  }

  // --- 3. Nessuna perdita ------------------------------------------------------------------------------------------------------------

  @Test
  fun unOriginaleSparitoEUnaPerdita_ChiEStatoEliminatoDiPropositoNo(): Unit = runBlocking {
    val w = world(0 to ("test" to "a.jpg"))
    w.ledger.add(Original.of(1, bytes(1))) // non e' sul NAS
    assertThat(Verifier.midState(scan(w), context(w)).invariants()).contains(Invariant.LOSS)
    assertThat(Verifier.midState(scan(w), context(w, survives = { it.id != 1 }))).isEmpty()
  }

  // --- 5. Temporanei -----------------------------------------------------------------------------------------------------------------

  @Test
  fun unTemporaneoNonCitatoDalDiarioOAltruiEUnaViolazione(): Unit = runBlocking {
    val w = world(0 to ("test" to "a.jpg"))
    val own = ".mycloudgallery/tmp/$device-op-xxxxxxxxxxxxxxxxxxxxxxxxxx-0"
    val foreign = ".mycloudgallery/tmp/${Base32.encode(ByteArray(16) { 0x44 })}-op-yy-0"
    w.world.nas.putFile("test-comune", own, NasContent.of(ByteArray(10)))
    val empty = FakeDiary()
    assertThat(Verifier.midState(scan(w), context(w, empty)).invariants()).contains(Invariant.TEMP)
    assertThat(Verifier.quiescent(scan(w), context(w, empty), null).invariants()).contains(Invariant.TEMP)
    // Citato da un elemento non finito, a meta' va bene; a operazioni finite non deve esserci piu'.
    val tempName = SharePath.parse(own)
    val cited = FakeDiary(items = listOf(crossShareItem(OpItemState.COPYING, w.ledger.byId(0).sha, tempName = tempName)))
    assertThat(Verifier.midState(scan(w), context(w, cited))).isEmpty()
    assertThat(Verifier.quiescent(scan(w), context(w, cited), null).invariants()).contains(Invariant.TEMP)
    w.world.nas.delete("test-comune", own)
    w.world.nas.putFile("test-comune", foreign, NasContent.of(ByteArray(10)))
    assertThat(Verifier.midState(scan(w), context(w, cited)).invariants()).contains(Invariant.TEMP)
  }

  // --- Il cestino e il suo diario ----------------------------------------------------------------------------------------------------

  @Test
  fun unFileNelCestinoVuoleLaSuaVoceNelDiarioELasuaVoceDeveDireTrashed(): Unit = runBlocking {
    val kit = NasOpsTestKit()
    val bytes = bytes(0)
    kit.putFile("test", "Foto/a.jpg", bytes)
    val ledger = Ledger().also { it.add(Original.of(0, bytes)) }
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val scanner = NasScanner(kit.nas.reader, ScanScope.wholeShares(shares), setOf(kit.deviceId))
    val context = VerifyContext(ledger, StoreDiary(kit.store), kit.deviceId, strictAppArea = true)
    val snapshot = scanner.scan()
    assertThat(snapshot.trash).hasSize(1)
    assertThat(Verifier.midState(snapshot, context)).isEmpty()

    // La voce del diario sparisce o dice un'altra cosa: il file nel cestino e' senza diario.
    val entry = kit.store.allTrash().single()
    kit.store.markTrash(entry.trashId, TrashEntryState.PURGED, 1)
    assertThat(Verifier.midState(snapshot, context).invariants()).contains(Invariant.TRASH_RECORD)
    val noEntry = VerifyContext(ledger, FakeDiary(), kit.deviceId, strictAppArea = true)
    assertThat(Verifier.midState(snapshot, noEntry).invariants()).contains(Invariant.TRASH_RECORD)
  }

  @Test
  fun ilRecordFirmatoDelCestinoSiPretendeSoloSeLoStackLoScrive(): Unit = runBlocking {
    val kit = NasOpsTestKit()
    val bytes = bytes(0)
    kit.putFile("test", "a.jpg", bytes)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"))))
    val ledger = Ledger().also { it.add(Original.of(0, bytes)) }
    val snapshot = NasScanner(kit.nas.reader, ScanScope.wholeShares(shares), setOf(kit.deviceId)).scan()
    val withRecords = VerifyContext(ledger, StoreDiary(kit.store), kit.deviceId, writesTrashRecords = true)
    assertThat(Verifier.midState(snapshot, withRecords).invariants()).contains(Invariant.TRASH_RECORD)
    // Il record c'e': trash/<dispositivo>/rec/<id>.
    val id = kit.store.allTrash().single().trashId
    kit.putFile("test", ".mycloudgallery/trash/${kit.deviceId}/rec/$id", byteArrayOf(1))
    val again = NasScanner(kit.nas.reader, ScanScope.wholeShares(shares), setOf(kit.deviceId)).scan()
    assertThat(Verifier.midState(again, withRecords)).isEmpty()
  }

  @Test
  fun unFileDellAppFuoriPostoEUnaViolazione_unInvolucroMcgwNelCestinoNo(): Unit = runBlocking {
    val kit = NasOpsTestKit()
    val bytes = bytes(0)
    val ledger = Ledger().also { it.add(Original.of(0, bytes)) }
    // Un file cestinato con l'involucro "MCGW" (8 byte davanti): l'impronta dei byte originali si riconosce lo stesso.
    kit.putFile("test", "a.jpg", bytes)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"))))
    val id = kit.store.allTrash().single().trashId
    val trashed = ".mycloudgallery/trash/${kit.deviceId}/files/$id"
    kit.nas.putFile("test", trashed, NasContent.of(byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 1, 0, 0, 0) + bytes))
    val context = VerifyContext(ledger, StoreDiary(kit.store), kit.deviceId, strictAppArea = true)
    assertThat(Verifier.midState(NasScanner(kit.nas.reader, ScanScope.wholeShares(shares), setOf(kit.deviceId)).scan(), context)).isEmpty()
    // Un file qualunque in .mycloudgallery/ che non e' ne' temporaneo ne' cestino proprio.
    kit.putFile("test", ".mycloudgallery/devices/x/index", byteArrayOf(1, 2, 3))
    assertThat(Verifier.midState(NasScanner(kit.nas.reader, ScanScope.wholeShares(shares), setOf(kit.deviceId)).scan(), context).invariants()).contains(Invariant.APP_AREA)
  }

  // --- 6. Catalogo coerente ----------------------------------------------------------------------------------------------------------

  @Test
  fun ilPostoSbagliatoOLaGrafiaSbagliataSonoUnaViolazione_unNomeAlternativoAmmessoNo(): Unit = runBlocking {
    val w = world(0 to ("test" to "Foto/b (2).jpg"))
    val sha = w.ledger.byId(0).sha
    fun expect(place: Place, tolerant: Boolean) =
      Expectation(mapOf(sha to ExpectedVisible(place, tolerant)), emptyMap(), emptySet(), emptySet(), emptyMap())
    val snapshot = scan(w)
    assertThat(Verifier.quiescent(snapshot, context(w), expect(Place("test", "Foto/b (2).jpg"), false))).isEmpty()
    assertThat(Verifier.quiescent(snapshot, context(w), expect(Place("test", "Foto/b.jpg"), false)).invariants()).contains(Invariant.CATALOG)
    assertThat(Verifier.quiescent(snapshot, context(w), expect(Place("test", "Foto/b.jpg"), true))).isEmpty()
    assertThat(Verifier.quiescent(snapshot, context(w), expect(Place("test", "FOTO/b (2).jpg"), false)).invariants()).contains(Invariant.CATALOG)
    assertThat(Verifier.quiescent(snapshot, context(w), expect(Place("test-comune", "Foto/b.jpg"), true)).invariants()).contains(Invariant.CATALOG)
  }

  @Test
  fun unaCartellaAttesaCheNonCeOConUnaGrafiaDiversaEUnaViolazione(): Unit = runBlocking {
    val w = world(0 to ("test" to "fOTO/a.jpg"))
    fun expectDir(dir: Place) =
      Expectation(mapOf(w.ledger.byId(0).sha to ExpectedVisible(null, true)), null, emptySet(), setOf(dir), null)
    val snapshot = scan(w)
    assertThat(Verifier.quiescent(snapshot, context(w), expectDir(Place("test", "fOTO"))).invariants()).doesNotContain(Invariant.CATALOG)
    assertThat(Verifier.quiescent(snapshot, context(w), expectDir(Place("test", "Foto"))).invariants()).contains(Invariant.CATALOG)
    assertThat(Verifier.quiescent(snapshot, context(w), expectDir(Place("test", "Altra"))).invariants()).contains(Invariant.CATALOG)
  }

  @Test
  fun o_b18_unaCartellaDOrigineCheDovevaSparireEUnaViolazione_seNonCeNessuna(): Unit = runBlocking {
    // V ha ancora un file (la cartella c'e'): se lo stack doveva averla tolta, e' una violazione; se non c'e' piu' no. Il file di V non fa rumore
    // per questo controllo (lo dicono gli altri invarianti); qui conta solo la cartella.
    val w = world(0 to ("test" to "V/a.jpg"))
    fun expectGone(dir: Place) =
      Expectation(mapOf(w.ledger.byId(0).sha to ExpectedVisible(null, true)), null, emptySet(), emptySet(), null, goneDirs = setOf(dir))
    val snapshot = scan(w)
    val still = Verifier.quiescent(snapshot, context(w), expectGone(Place("test", "V")))
    assertThat(still.map { it.detail }.any { it.contains("e' rimasta, vuota") }).isTrue()
    assertThat(Verifier.quiescent(snapshot, context(w), expectGone(Place("test", "Altra")))).isEmpty()
    // Maiuscole e minuscole non cambiano la risposta: Samba non le distingue.
    assertThat(Verifier.quiescent(snapshot, context(w), expectGone(Place("test", "v"))).invariants()).contains(Invariant.CATALOG)
  }

  @Test
  fun o_b18_ilModelloRicordaLeCartelleDOrigineEleRivive() {
    val model = FsModel()
    model.addDir(Place("test", "V/Gita"))
    model.expectGone(Place("test", "V"))
    assertThat(model.goneDirs.keys).containsExactly(Place("test", "V").key, Place("test", "V/Gita").key)
    // Un'altra cartella o un'altra share non c'entrano.
    model.addDir(Place("test-comune", "V"))
    assertThat(model.goneDirs).hasSize(2)
    // Qualcosa che torna a vivere sotto V (un ripristino, un file spostato dentro) la riporta fra quelle che possono esserci, con le sue madri.
    model.addDir(Place("test", "V/Gita"))
    assertThat(model.goneDirs).isEmpty()
  }

  // --- La lettura del NAS ------------------------------------------------------------------------------------------------------------

  @Test
  fun unaLetturaCheNonRiesceNonPassaInSilenzio(): Unit = runBlocking {
    val w = world(0 to ("test" to "a.jpg"))
    w.world.nas.failWith = NasError.Timeout
    val snapshot = scan(w)
    assertThat(snapshot.problems).isNotEmpty()
    assertThat(Verifier.midState(snapshot, context(w)).invariants()).contains(Invariant.SCAN)
  }

  // --- Il diario ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilDiarioACostoNonHaOperazioniAperteNeFalliteNeAnnullate(): Unit = runBlocking {
    val kit = NasOpsTestKit()
    kit.putFile("test", "a.jpg", bytes(0))
    kit.run(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    val context = VerifyContext(Ledger(), StoreDiary(kit.store), kit.deviceId)
    assertThat(Verifier.diary(context, allowFailed = false, allowCancelled = false, expectedTrash = emptySet())).isEmpty()
    // Un'operazione lasciata a meta' nel diario.
    kit.putFile("test", "c.jpg", bytes(1))
    val plan = (kit.operations.plan(OpRequest.Rename(kit.itemOf("test", "c.jpg"), "d.jpg")) as PlanOutcome.Ready).plan
    kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection)
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.opsFaults = null
    assertThat(Verifier.diary(context, false, false, null).invariants()).contains(Invariant.CONVERGENCE)
  }

  // --- I colpi -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaMorteDelProcessoFermaPerSempreLeChiamateDiQuelProcesso(): Unit = runBlocking {
    val injector = Injector()
    val incarnation = Incarnation(0)
    injector.load(listOf(FaultRule(Trigger.OfPrimitive(Primitive.MKDIRS, 2), Moment.AFTER, FaultKind.KILL)))
    var applied = 0
    val call: suspend () -> dev.pampa.mycloudgallery.core.nas.contract.NasResult<Unit> = {
      applied++
      dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok(Unit)
    }
    injector.nas(Primitive.MKDIRS, incarnation, call)
    val died = runCatching { injector.nas(Primitive.MKDIRS, incarnation, call) }.exceptionOrNull()
    assertThat(died).isInstanceOf(ProcessDeath::class.java)
    // "Dopo" vuol dire che la modifica c'e' stata e la risposta no.
    assertThat(applied).isEqualTo(2)
    assertThat(incarnation.dead).isTrue()
    // Un finally o una coroutine rimasta non toccano piu' il NAS.
    val zombie = runCatching { injector.nas(Primitive.MKDIRS, incarnation, call) }.exceptionOrNull()
    assertThat(zombie).isInstanceOf(ProcessDeath::class.java)
    assertThat(applied).isEqualTo(2)
    // Un'altra incarnazione lavora.
    injector.nas(Primitive.MKDIRS, Incarnation(1), call)
    assertThat(applied).isEqualTo(3)
  }

  @Test
  fun unColpoPrimaNonApplicaEUnColpoDopoApplicaMaNascondeLaRisposta(): Unit = runBlocking {
    val injector = Injector()
    val incarnation = Incarnation(0)
    injector.load(
      listOf(
        FaultRule(Trigger.NasCall(1), Moment.BEFORE, FaultKind.DROP),
        FaultRule(Trigger.NasCall(2), Moment.AFTER, FaultKind.EXPIRE_SESSION),
        FaultRule(Trigger.NasCall(3), Moment.BEFORE, FaultKind.STALL),
      ),
    )
    var applied = 0
    val call: suspend () -> dev.pampa.mycloudgallery.core.nas.contract.NasResult<Unit> = {
      applied++
      dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok(Unit)
    }
    val first = injector.nas(Primitive.MKDIRS, incarnation, call)
    assertThat(first).isEqualTo(dev.pampa.mycloudgallery.core.nas.contract.NasResult.Err(NasError.Timeout))
    assertThat(applied).isEqualTo(0)
    val second = injector.nas(Primitive.RENAME, incarnation, call)
    assertThat(second).isEqualTo(dev.pampa.mycloudgallery.core.nas.contract.NasResult.Err(NasError.SessionExpired))
    assertThat(applied).isEqualTo(1)
    val third = injector.nas(Primitive.STAT, incarnation, call)
    assertThat(third).isEqualTo(dev.pampa.mycloudgallery.core.nas.contract.NasResult.Err(NasError.Stalled))
    assertThat(applied).isEqualTo(1)
    assertThat(injector.fired.map { it.kind }).containsExactly(FaultKind.DROP, FaultKind.EXPIRE_SESSION, FaultKind.STALL).inOrder()
    // Disarmato non conta e non colpisce.
    injector.disarm()
    assertThat(injector.nas(Primitive.MKDIRS, incarnation, call)).isEqualTo(dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok(Unit))
  }

  // --- Aiuti -------------------------------------------------------------------------------------------------------------------------

  private class FakeDiary(private val items: List<OpItemRecord> = emptyList()) : DiaryView {
    override suspend fun ops(): List<OpRecord> = emptyList()

    override suspend fun items(): List<OpItemRecord> = items

    override suspend fun trashEntry(trashId: String): LocalTrashRecord? = null

    override suspend fun trashed(): List<LocalTrashRecord> = emptyList()
  }

  private fun crossShareItem(state: OpItemState, sha: Sha256, tempName: SharePath? = null): OpItemRecord = OpItemRecord(
    opId = dev.pampa.mycloudgallery.core.nasops.contract.OpId(Base32.encode(ByteArray(16) { 1 })),
    index = 0,
    kind = OpKind.MOVE,
    state = state,
    isFolder = false,
    accountId = "test",
    srcShare = "test",
    srcPath = SharePath.parse("a.jpg"),
    dstShare = "test-comune",
    dstPath = SharePath.parse("Copia/a.jpg"),
    tempName = tempName,
    size = 400,
    stamp = null,
    sha256 = sha,
    trashId = Base32.encode(ByteArray(16) { 2 }),
    attempts = 0,
    nextAttemptAtNas = null,
    errorCode = null,
    updatedAtNas = 0,
  )
}
