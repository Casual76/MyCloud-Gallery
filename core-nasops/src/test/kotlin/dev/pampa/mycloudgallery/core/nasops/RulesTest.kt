package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nasops.PlanFixtures.id
import dev.pampa.mycloudgallery.core.nasops.PlanFixtures.me
import dev.pampa.mycloudgallery.core.nasops.PlanFixtures.other
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LockedBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFolderBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRules
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanViolation
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.trust.data.OwnFile
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Test

/** Le regole pure di core-nasops: impronta del piano, controlli del piano, macchina a stati, nomi, attese, temporanei, codici stabili. */
class RulesTest {

  // --- Impronta e controlli del piano -----------------------------------------------------------------------------------------

  @Test
  fun lImprontaDelPianoEDeterministicaECambiaConOgniElemento() {
    assertThat(PlanFixtures.trash(2).hash).isEqualTo(PlanFixtures.trash(2).hash)
    assertThat(PlanFixtures.trash(2).hash).isNotEqualTo(PlanFixtures.trash(3).hash)
    assertThat(PlanFixtures.rename("a.jpg", "b.jpg").hash).isNotEqualTo(PlanFixtures.rename("a.jpg", "c.jpg").hash)
    assertThat(PlanFixtures.trash(1, share = "test").hash).isNotEqualTo(PlanFixtures.trash(1, share = "test-comune").hash)
    assertThat(PlanFixtures.trash().hash.hex).matches("^[0-9a-f]{64}$")
  }

  @Test
  fun unPianoInOrdinePassaITutti() {
    assertThat(PlanRules.firstViolation(PlanFixtures.trash(3), me)).isNull()
    assertThat(PlanRules.firstViolation(PlanFixtures.rename("a.jpg", "b.jpg"), me)).isNull()
    assertThat(PlanRules.firstViolation(PlanFixtures.move("Foto/a.jpg", "Foto/a.jpg"), me)).isNull()
    assertThat(PlanRules.firstViolation(PlanFixtures.purge(), me)).isNull()
    assertThat(PlanRules.firstViolation(PlanFixtures.restore(), me)).isNull()
    assertThat(PlanRules.firstViolation(PlanFixtures.create("Nuova"), me)).isNull()
  }

  @Test
  fun ilPianoSbagliatoNonPartePerNienteDiCioCheLeggeIlNas() {
    val empty = OpPlan(OpKind.TRASH, emptyList(), emptyList(), PlanSummary(OpKind.TRASH, 0, 0, 0, null, null), emptySet())
    assertThat(PlanRules.firstViolation(empty, me)).isEqualTo(PlanViolation.EMPTY)

    // Mai un'operazione sui file dell'app.
    assertThat(PlanRules.firstViolation(PlanFixtures.rename(".mycloudgallery/devices/x", "b.jpg"), me)).isEqualTo(PlanViolation.APP_AREA_PATH)
    assertThat(PlanRules.firstViolation(PlanFixtures.rename("a.jpg", ".mycloudgallery/b"), me)).isEqualTo(PlanViolation.APP_AREA_PATH)
    assertThat(PlanRules.firstViolation(PlanFixtures.create(".mycloudgallery/nuova"), me)).isEqualTo(PlanViolation.APP_AREA_PATH)
    assertThat(PlanRules.firstViolation(PlanFixtures.restore(to = ".mycloudgallery/x"), me)).isEqualTo(PlanViolation.APP_AREA_PATH)

    assertThat(PlanRules.firstViolation(PlanFixtures.rename("a.jpg", "a.jpg"), me)).isEqualTo(PlanViolation.SAME_LOCATION)
  }

  @Test
  fun eliminaSubitoVaSoloSulCestinoProprioEPuntaAUnaVoceDelCestino() {
    // Il cestino di un altro dispositivo non e' mio: nemmeno se la voce "esiste".
    assertThat(PlanRules.firstViolation(PlanFixtures.purge(owner = other), me)).isEqualTo(PlanViolation.NOT_OWN_TRASH)
    // Un percorso qualunque al posto del cestino.
    val notTrash = PlannedItem(0, ItemFlow.PURGE, "test", SharePath.parse("Foto/a.jpg"), null, null, false, 10, null, trashId = id(3))
    val plan = OpPlan(OpKind.PURGE_NOW, listOf(notTrash), emptyList(), PlanSummary(OpKind.PURGE_NOW, 1, 0, 10, null, null), emptySet())
    assertThat(PlanRules.firstViolation(plan, me)).isEqualTo(PlanViolation.NOT_A_TRASH_ITEM)
    // L'id della voce deve essere quello del percorso.
    val mismatch = PlannedItem(0, ItemFlow.PURGE, "test", NasLayout.trashItemFile(me, id(3)), null, null, false, 10, null, trashId = id(4))
    val plan2 = OpPlan(OpKind.PURGE_NOW, listOf(mismatch), emptyList(), PlanSummary(OpKind.PURGE_NOW, 1, 0, 10, null, null), emptySet())
    assertThat(PlanRules.firstViolation(plan2, me)).isEqualTo(PlanViolation.NOT_OWN_TRASH)
  }

  @Test
  fun ilCamminoDeveEssereQuelloDelTipoDiOperazione() {
    val item = PlannedItem(0, ItemFlow.TRASH, "test", SharePath.parse("a.jpg"), null, null, false, 1, null, trashId = id(1))
    val plan = OpPlan(OpKind.RENAME, listOf(item), emptyList(), PlanSummary(OpKind.RENAME, 1, 0, 1, null, null), emptySet())
    assertThat(PlanRules.firstViolation(plan, me)).isEqualTo(PlanViolation.WRONG_FLOW)
  }

  @Test
  fun unaCartellaNonSiSpostaDentroSeStessa() {
    val item = PlannedItem(0, ItemFlow.RENAME, "test", SharePath.parse("Gita"), "test", SharePath.parse("Gita/Sotto"), true, 0, null)
    val plan = OpPlan(OpKind.MOVE, listOf(item), emptyList(), PlanSummary(OpKind.MOVE, 1, 1, 0, null, null), emptySet())
    assertThat(PlanRules.firstViolation(plan, me)).isEqualTo(PlanViolation.INTO_ITSELF)
  }

  // --- Macchina a stati ----------------------------------------------------------------------------------------------------------

  @Test
  fun ogniCamminoParteDaPlannedEFinisceInDone() {
    for (flow in ItemFlow.entries) {
      assertWithMessage(flow.name).that(flow.steps.first()).isEqualTo(OpItemState.PLANNED)
      assertWithMessage(flow.name).that(flow.steps.last()).isEqualTo(OpItemState.DONE)
      assertWithMessage(flow.name).that(flow.steps.toSet()).hasSize(flow.steps.size)
      var state = flow.steps.first()
      while (true) state = OpStateMachine.next(flow, state) ?: break
      assertThat(state).isEqualTo(OpItemState.DONE)
    }
    assertThat(ItemFlow.MOVE_ACROSS_SHARES.steps.map { it.code }).containsExactly(
      "planned", "copying", "copied", "verified", "published", "source_recorded", "source_trashed", "done",
    ).inOrder()
  }

  @Test
  fun leTransizioniAmmesseSonoSoloAvantiDiUnPassoOFailedOCancellabile() {
    val move = ItemFlow.MOVE_ACROSS_SHARES
    assertThat(OpStateMachine.canTransition(move, OpItemState.PLANNED, OpItemState.COPYING)).isTrue()
    assertThat(OpStateMachine.canTransition(move, OpItemState.COPYING, OpItemState.COPYING)).isTrue()
    assertThat(OpStateMachine.canTransition(move, OpItemState.PLANNED, OpItemState.COPIED)).isFalse()
    assertThat(OpStateMachine.canTransition(move, OpItemState.VERIFIED, OpItemState.COPIED)).isFalse()
    assertThat(OpStateMachine.canTransition(move, OpItemState.COPIED, OpItemState.FAILED)).isTrue()
    assertThat(OpStateMachine.canTransition(move, OpItemState.DONE, OpItemState.PLANNED)).isFalse()
    assertThat(OpStateMachine.canTransition(move, OpItemState.FAILED, OpItemState.DONE)).isFalse()
    // Un passo di un altro cammino non c'e'.
    assertThat(OpStateMachine.canTransition(ItemFlow.RENAME, OpItemState.PLANNED, OpItemState.TRASHED)).isFalse()
  }

  @Test
  fun annullareLasciaDoppioniVisibiliSoloDopoLaPubblicazione() {
    val move = ItemFlow.MOVE_ACROSS_SHARES
    for (state in listOf(OpItemState.PLANNED, OpItemState.COPYING, OpItemState.COPIED, OpItemState.VERIFIED)) {
      assertWithMessage(state.name).that(OpStateMachine.cancellable(move, state)).isTrue()
    }
    for (state in listOf(OpItemState.PUBLISHED, OpItemState.SOURCE_RECORDED, OpItemState.SOURCE_TRASHED, OpItemState.DONE)) {
      assertWithMessage(state.name).that(OpStateMachine.cancellable(move, state)).isFalse()
    }
    // Gli altri cammini si annullano solo prima di partire.
    assertThat(OpStateMachine.cancellable(ItemFlow.RENAME, OpItemState.PLANNED)).isTrue()
    assertThat(OpStateMachine.cancellable(ItemFlow.RENAME, OpItemState.RENAMING)).isFalse()
    assertThat(OpStateMachine.cancellable(ItemFlow.TRASH, OpItemState.RECORDED)).isFalse()
  }

  @Test
  fun ilCamminoSiRicavaDalTipoDallaShareEDaiNomi() {
    val src = SharePath.parse("Foto/a.jpg")
    assertThat(OpStateMachine.flowOf(OpKind.TRASH, "test", null, src, null)).isEqualTo(ItemFlow.TRASH)
    assertThat(OpStateMachine.flowOf(OpKind.MOVE, "test", "test", src, SharePath.parse("Altro/a.jpg"))).isEqualTo(ItemFlow.RENAME)
    assertThat(OpStateMachine.flowOf(OpKind.MOVE, "test", "test-comune", src, SharePath.parse("Altro/a.jpg"))).isEqualTo(ItemFlow.MOVE_ACROSS_SHARES)
    assertThat(OpStateMachine.flowOf(OpKind.RENAME, "test", "test", src, SharePath.parse("Foto/A.JPG"))).isEqualTo(ItemFlow.RENAME_CASE_ONLY)
    assertThat(OpStateMachine.flowOf(OpKind.RENAME, "test", "test", src, SharePath.parse("Foto/b.jpg"))).isEqualTo(ItemFlow.RENAME)
    assertThat(OpStateMachine.flowOf(OpKind.PURGE_NOW, "test", null, src, null)).isEqualTo(ItemFlow.PURGE)
  }

  // --- Nomi, attese, temporanei -----------------------------------------------------------------------------------------------

  @Test
  fun iNomiNonValidiHannoUnaCorrezioneProposta() {
    assertThat(NameRules.check("Vacanze 2024")).isEqualTo(NameRules.Check.Valid)
    assertThat(NameRules.check("a:b?c")).isEqualTo(NameRules.Check.Invalid("a_b_c"))
    assertThat(NameRules.check("fine.")).isEqualTo(NameRules.Check.Invalid("fine"))
    assertThat(NameRules.check("tre  ")).isEqualTo(NameRules.Check.Invalid("tre"))
    assertThat(NameRules.check("")).isEqualTo(NameRules.Check.Invalid(null))
    assertThat(NameRules.check("...")).isEqualTo(NameRules.Check.Invalid(null))
    assertThat(NameRules.check("CON")).isInstanceOf(NameRules.Check.Invalid::class.java)
  }

  @Test
  fun iNomiAlternativiTengonoLEstensione() {
    assertThat(NameRules.alternative("foto.jpg", 2)).isEqualTo("foto (2).jpg")
    assertThat(NameRules.alternative("archivio.tar.gz", 3)).isEqualTo("archivio.tar (3).gz")
    assertThat(NameRules.alternative("Gita", 2)).isEqualTo("Gita (2)")
    assertThat(NameRules.alternative(".nascosto", 2)).isEqualTo(".nascosto (2)")
  }

  @Test
  fun leAttesePerUnFileBloccatoCrescono_eSiFermanoA24Ore() {
    val delays = ArrayList<Long>()
    var attempts = 0
    while (true) {
      val decision = LockedBackoff.decide(attempts)
      if (decision is LockedBackoff.Decision.RetryAfter) delays += decision.delayMs else break
      attempts++
    }
    assertThat(delays.first()).isEqualTo(60_000L)
    assertThat(delays).isInOrder()
    assertThat(delays.sum()).isEqualTo(LockedBackoff.MAX_TOTAL_WAIT_MS)
    assertThat(LockedBackoff.decide(attempts)).isEqualTo(LockedBackoff.Decision.GiveUp)
    assertThat(LockedBackoff.decide(1_000)).isEqualTo(LockedBackoff.Decision.GiveUp)
  }

  @Test
  fun leAtteseBreviPerUnaCartellaInUsoDallAppSonoSecondiECrescono() {
    // Decisione dell'utente (15/10): 1, 2, 4, 8, 16, 32 secondi, circa un minuto; molto meno della prima attesa per un file aperto dal PC.
    assertThat(OwnFolderBackoff.DELAYS_MS).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L).inOrder()
    assertThat(OwnFolderBackoff.MAX_TOTAL_WAIT_MS).isEqualTo(63_000L)
    assertThat(OwnFolderBackoff.MAX_TOTAL_WAIT_MS).isAtMost(65_000L)
    assertThat(OwnFolderBackoff.DELAYS_MS.max()).isLessThan(LockedBackoff.DELAYS_MS.first())
  }

  @Test
  fun iTemporaneiSonoDellaPuliziaDeiPropriFile() {
    val opId = OpId(id(5))
    val temp = OpTemps.tempFor(me, opId, 3)
    assertThat(OwnFile.classify(temp)).isInstanceOf(OwnFile.Temp::class.java)
    assertThat(NasOpsPaths.isTempOf(temp, me)).isTrue()
    assertThat(NasOpsPaths.isTempOf(temp, other)).isFalse()
    // Il nome della rinomina solo di maiuscole sta nella cartella dell'elemento, fuori dall'area dell'app.
    val swap = OpTemps.caseSwapName(SharePath.parse("Foto/a.jpg"), opId, 3)
    assertThat(swap.parent()).isEqualTo(SharePath.parse("Foto"))
    assertThat(NasOpsPaths.isMediaPath(swap)).isTrue()
    assertThat(OpTemps.caseSwapName(SharePath.parse("a.jpg"), opId, 0).parent()).isNull()
  }

  @Test
  fun ilCancelloDelleScrittureDistingueUtenteEFunzioniAutomatiche() {
    assertThat(GateStatus.OPEN.blocksUserOps).isFalse()
    assertThat(GateStatus.OPEN.blocksScheduled).isFalse()
    // Congelamento ed emergenza fermano tutto; il salto dell'ora ferma solo le pulizie e le funzioni a scadenza (D32).
    assertThat(GateStatus.FROZEN.blocksUserOps).isTrue()
    assertThat(GateStatus.WRITES_STOPPED.blocksUserOps).isTrue()
    assertThat(GateStatus.CLOCK_JUMP.blocksUserOps).isFalse()
    assertThat(GateStatus.CLOCK_JUMP.blocksScheduled).isTrue()
  }

  // --- Codici stabili ------------------------------------------------------------------------------------------------------------

  private val codes: Map<String, List<StableCode>> = mapOf(
    "OpKind" to OpKind.entries,
    "OpState" to OpState.entries,
    "OpItemState" to OpItemState.entries,
    "ItemFlow" to ItemFlow.entries,
    "OpErrorCode" to OpErrorCode.entries,
    "TrashReason" to TrashReason.entries,
    "TrashEntryState" to TrashEntryState.entries,
    "PauseReason" to PauseReason.entries,
    "RunStop" to RunStop.entries,
    "PurgeStop" to PurgeStop.entries,
    "GateStatus" to GateStatus.entries,
    "PlanRejection" to PlanRejection.entries,
    "PlanViolation" to PlanViolation.entries,
    "SkipReason" to SkipReason.entries,
  )

  @Test
  fun ogniCodiceEUnicoNelSuoEnumENelFormatoAmmesso() {
    codes.forEach { (name, values) ->
      val list = values.map { it.code }
      assertWithMessage(name).that(list.toSet().size).isEqualTo(list.size)
      list.forEach { assertWithMessage(name).that(it).matches("^[a-z0-9_]{1,32}$") }
    }
  }

  /** I codici si salvano nel diario (`nas_op`, `nas_op_item`, `nas_trash_entry`): cambiarli rompe i database esistenti. */
  @Test
  fun iCodiciSalvatiNelDiarioNonCambiano() {
    assertThat(OpKind.entries.map { it.code }).containsAtLeast("create_folder", "rename", "move", "trash", "restore", "purge_now")
    assertThat(OpState.entries.map { it.code }).containsAtLeast("planned", "running", "paused", "done", "failed", "cancelled")
    assertThat(OpItemState.entries.map { it.code }).containsAtLeast(
      "planned", "renaming", "temp_name", "copying", "copied", "verified", "published", "source_recorded", "source_trashed",
      "recorded", "trashed", "restoring", "purging", "done", "failed", "cancelled",
    )
    assertThat(TrashReason.entries.map { it.code }).containsExactly("delete", "move")
    assertThat(TrashEntryState.entries.map { it.code }).containsExactly("trashed", "restored", "purged")
    assertThat(OpErrorCode.entries.map { it.code }).containsAtLeast("source_changed", "locked", "locked_too_long", "verify_failed")
  }

  /** Prova F16: il motivo della pausa sta nella stessa colonna `error_code` dei codici d'errore, quindi i due elenchi non si toccano. */
  @Test
  fun iMotiviDellaPausaNonSiConfondonoConICodiciDErrore() {
    val errors = OpErrorCode.entries.map { it.code }.toSet()
    assertThat(PauseReason.entries.map { it.code }.filter { it in errors }).isEmpty()
    assertThat(PauseReason.entries.map { it.code }).containsAtLeast("nas_unreachable", "conditions", "frozen", "clock_jump", "writes_stopped")
  }

  @Test
  fun ilNomeDelCamminoNonDipendeDalleMaiuscoleDelleShare() {
    val a = PlannedItem(0, ItemFlow.RENAME, "Test", SharePath.parse("a"), "TEST", SharePath.parse("b"), false, 1, NasStamp(1, 2, 3))
    assertThat(OpStateMachine.flowOf(OpKind.MOVE, a.srcShare, a.dstShare, a.srcPath, a.dstPath)).isEqualTo(ItemFlow.RENAME)
    assertThat(PlanHash.of(OpKind.MOVE, listOf(a))).isEqualTo(PlanHash.of(OpKind.MOVE, listOf(a)))
  }
}
