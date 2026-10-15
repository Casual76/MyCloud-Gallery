package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LockedBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanViolation
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le operazioni sul NAS finto con l'esecutore di riferimento: ogni macchina a stati arriva in fondo, il diario scrive i passi nell'ordine,
 * le regole del progetto (E-D*, E-N*) tengono. Da P12 gli stessi scenari girano sull'esecutore vero.
 */
class OperationsTest {

  private val kit = NasOpsTestKit()
  private val a = byteArrayOf(1, 2, 3, 4)
  private val b = byteArrayOf(9, 8, 7)

  private suspend fun plan(request: OpRequest): OpPlan = (kit.operations.plan(request) as PlanOutcome.Ready).plan

  private suspend fun rejection(request: OpRequest): PlanRejection = (kit.operations.plan(request) as PlanOutcome.Rejected).reason

  private fun visible(share: String): List<String> = kit.filesIn(share).filterNot { it.startsWith(".mycloudgallery") }

  private fun appFiles(share: String): List<String> = kit.filesIn(share).filter { it.startsWith(".mycloudgallery") }

  private fun itemSteps(index: Int = 0): List<String> = kit.store.history.filter { it.index == index }.map { it.to.code }

  private fun lastState() = kit.operations.operations.value.last()

  // --- Crea cartella, rinomina, sposta nella stessa share ----------------------------------------------------------------------

  @Test
  fun creaUnaCartella() = runTest {
    assertThat(kit.run(OpRequest.CreateFolder("test", SharePath.parse("Foto"), "Nuova"))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.exists("test", "Foto/Nuova")).isTrue()
    assertThat(lastState()).isInstanceOf(NasOperationState.Done::class.java)
    assertThat(itemSteps()).containsExactly("done")
  }

  @Test
  fun creaUnaCartellaCheCeGiaOConUnNomeNonValidoNonDiventaUnPiano() = runTest {
    kit.putDir("test", "Foto/Nuova")
    assertThat(rejection(OpRequest.CreateFolder("test", SharePath.parse("Foto"), "Nuova"))).isEqualTo(PlanRejection.NAME_EXISTS)
    assertThat(rejection(OpRequest.CreateFolder("test", null, "a:b"))).isEqualTo(PlanRejection.INVALID_NAME)
    kit.readOnlyShares += "test"
    assertThat(rejection(OpRequest.CreateFolder("test", null, "Altra"))).isEqualTo(PlanRejection.READ_ONLY)
  }

  @Test
  fun rinominaUnFileConservandoLIdentita() = runTest {
    val stamp = kit.putFile("test", "Foto/a.jpg", a)
    val item = kit.itemOf("test", "Foto/a.jpg")
    assertThat(kit.run(OpRequest.Rename(item, "b.jpg"))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(visible("test")).containsExactly("Foto/b.jpg")
    assertThat(kit.bytes("test", "Foto/b.jpg")).isEqualTo(a)
    assertThat(kit.itemOf("test", "Foto/b.jpg").stamp!!.fileId).isEqualTo(stamp.fileId)
    assertThat(itemSteps()).containsExactly("renaming", "done").inOrder()
  }

  @Test
  fun rinominaSoloDiMaiuscolePassandoDaUnNomeTemporaneo() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    val item = kit.itemOf("test", "Foto/a.jpg")
    val plan = plan(OpRequest.Rename(item, "A.JPG"))
    assertThat(plan.items.single().flow).isEqualTo(ItemFlow.RENAME_CASE_ONLY)
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(visible("test")).containsExactly("Foto/A.JPG")
    assertThat(itemSteps()).containsExactly("temp_name", "done").inOrder()
    // Il nome temporaneo sta nel diario e il NAS non ne ha piu' traccia.
    assertThat(kit.store.items(kit.store.ops(setOf(OpState.DONE)).single().id).single().tempName).isNotNull()
  }

  @Test
  fun rinominareSuUnNomeOccupatoENonUnPiano() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.putFile("test", "b.jpg", b)
    assertThat(rejection(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))).isEqualTo(PlanRejection.NAME_EXISTS)
    assertThat(rejection(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "a.jpg"))).isEqualTo(PlanRejection.SAME_LOCATION)
  }

  @Test
  fun spostaNellaStessaShareConUnNomeOccupatoUsaUnNomeAlternativoScrittoNelDiario() = runTest {
    kit.putFile("test", "A/x.jpg", a)
    kit.putFile("test", "B/x.jpg", b)
    val item = kit.itemOf("test", "A/x.jpg")
    assertThat(kit.run(OpRequest.Move(listOf(item), "test", SharePath.parse("B")))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(visible("test")).containsExactly("B/x.jpg", "B/x (2).jpg")
    assertThat(kit.bytes("test", "B/x.jpg")).isEqualTo(b)
    assertThat(kit.bytes("test", "B/x (2).jpg")).isEqualTo(a)
    val record = kit.store.items(kit.store.ops(setOf(OpState.DONE)).single().id).single()
    assertThat(record.dstPath).isEqualTo(SharePath.parse("B/x (2).jpg"))
  }

  @Test
  fun spostareUnaCartellaDentroSeStessaESaltatoELoDice() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    val folder = kit.itemOf("test", "Gita")
    assertThat(rejection(OpRequest.Move(listOf(folder), "test", SharePath.parse("Gita")))).isEqualTo(PlanRejection.INTO_ITSELF)
    kit.putFile("test", "Altro/2.jpg", b)
    val mixed = plan(OpRequest.Move(listOf(folder, kit.itemOf("test", "Altro/2.jpg")), "test", SharePath.parse("Gita")))
    assertThat(mixed.items).hasSize(1)
    assertThat(mixed.skipped.single().reason).isEqualTo(SkipReason.INTO_ITSELF)
    assertThat(mixed.warnings).contains(PlanWarning.SOME_SKIPPED)
  }

  // --- Sposta tra share ---------------------------------------------------------------------------------------------------------

  @Test
  fun spostaTraShareConCopiaVerificaPubblicazioneESorgenteNelCestino() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    val item = kit.itemOf("test", "Viaggi/a.jpg")
    val plan = plan(OpRequest.Move(listOf(item), "test-comune", SharePath.parse("Famiglia")))
    assertThat(plan.warnings).contains(PlanWarning.CROSS_SHARE_COPY)
    assertThat(plan.items.single().flow).isEqualTo(ItemFlow.MOVE_ACROSS_SHARES)
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)

    assertThat(visible("test-comune")).containsExactly("Famiglia/a.jpg")
    assertThat(kit.bytes("test-comune", "Famiglia/a.jpg")).isEqualTo(a)
    assertThat(visible("test")).isEmpty()
    // La sorgente e' nel cestino per 30 giorni (Q3), con la voce nel diario locale.
    val entry = kit.store.allTrash().single()
    assertThat(entry.state).isEqualTo(TrashEntryState.TRASHED)
    assertThat(entry.reason).isEqualTo(TrashReason.MOVE)
    assertThat(kit.bytes("test", entry.trashedPath.value)).isEqualTo(a)
    // Nessun temporaneo rimasto.
    assertThat(appFiles("test-comune")).isEmpty()
    assertThat(itemSteps()).containsExactly("copying", "copied", "verified", "published", "source_recorded", "source_trashed", "done").inOrder()
    assertThat(lastState()).isInstanceOf(NasOperationState.Done::class.java)
  }

  @Test
  fun conLaCopiaLatoServerRifiutataSiRipiegaDalTelefono() = runTest {
    kit.nas.serverCopyMode = ServerCopyMode.ACCESS_DENIED
    kit.putFile("test", "Viaggi/a.jpg", a)
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))
    assertThat(kit.bytes("test-comune", "a.jpg")).isEqualTo(a)
    assertThat(kit.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(1)
    assertThat(visible("test")).isEmpty()
    assertThat(appFiles("test-comune")).isEmpty()
  }

  @Test
  fun unaCartellaTraShareSiEspandeInFile() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    kit.putFile("test", "Gita/Mare/2.jpg", b)
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "Gita")), "test-comune", SharePath.parse("Famiglia")))
    assertThat(visible("test-comune")).containsExactly("Famiglia/Gita/1.jpg", "Famiglia/Gita/Mare/2.jpg")
    assertThat(visible("test")).isEmpty()
    assertThat(kit.store.allTrash()).hasSize(2)
  }

  @Test
  fun seLaSorgenteCambiaMentreSiCopiaLElementoSiFermaESenzaCestino() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    val item = kit.itemOf("test", "Viaggi/a.jpg")
    val plan = plan(OpRequest.Move(listOf(item), "test-comune", null))
    // Il PC modifica il file proprio mentre l'app lo verifica (E-D13).
    var modified = false
    kit.nas.fault = FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.OPEN && !modified) {
        modified = true
        kit.modify("test", "Viaggi/a.jpg", byteArrayOf(5, 5, 5))
      }
      null
    }
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.fault = null

    val state = lastState()
    assertThat(state).isInstanceOf(NasOperationState.Failed::class.java)
    assertThat((state as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_CHANGED)
    // Entrambe le copie del mondo restano com'erano: la sorgente (modificata), niente in destinazione, niente nel cestino, niente temporanei.
    assertThat(kit.bytes("test", "Viaggi/a.jpg")).isEqualTo(byteArrayOf(5, 5, 5))
    assertThat(visible("test-comune")).isEmpty()
    assertThat(kit.store.allTrash()).isEmpty()
    assertThat(appFiles("test-comune")).isEmpty()
  }

  @Test
  fun spazioInsufficienteSullaDestinazioneNonFaIniziareNiente() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", ByteArray(1_000))
    kit.nas.spaceOf = { dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo(10_000, 100, 100) }
    assertThat(rejection(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))).isEqualTo(PlanRejection.NOT_ENOUGH_SPACE)
    assertThat(visible("test")).containsExactly("Viaggi/a.jpg")
  }

  // --- Elimina, ripristina, elimina subito ----------------------------------------------------------------------------------------

  @Test
  fun eliminaMetteNelCestinoERipristinaLoRiporta() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    assertThat(visible("test")).isEmpty()
    val entry = kit.store.allTrash().single()
    assertThat(entry.reason).isEqualTo(TrashReason.DELETE)
    assertThat(kit.bytes("test", entry.trashedPath.value)).isEqualTo(a)
    assertThat(itemSteps()).containsExactly("recorded", "trashed", "done").inOrder()

    kit.trash.refresh()
    val shown = (kit.trash.items.value as NasTrashState.Ready).items.single()
    assertThat(shown.deletableNow).isTrue()
    assertThat(shown.restorable).isTrue()
    assertThat(shown.daysLeft).isEqualTo(30)

    val restore = OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, false, entry.size)))
    kit.run(restore)
    assertThat(visible("test")).containsExactly("Foto/a.jpg")
    assertThat(kit.store.trashEntry(entry.trashId)!!.state).isEqualTo(TrashEntryState.RESTORED)
  }

  @Test
  fun ilRipristinoRicreaLaCartellaSparitaENonSostituisceMai() = runTest {
    kit.putFile("test", "Foto/2024/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/2024/a.jpg"))))
    val entry = kit.store.allTrash().single()
    kit.deleteFromPc("test", "Foto")
    // Cartella d'origine sparita (E-D3) e un altro file al suo posto: nome alternativo.
    kit.putFile("test", "Foto/2024/a.jpg", b)
    kit.run(OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, false, entry.size))))
    assertThat(visible("test")).containsExactly("Foto/2024/a.jpg", "Foto/2024/a (2).jpg")
    assertThat(kit.bytes("test", "Foto/2024/a.jpg")).isEqualTo(b)
    assertThat(kit.bytes("test", "Foto/2024/a (2).jpg")).isEqualTo(a)
  }

  @Test
  fun eliminareUnaCartellaEUnaSolaRinominaERipristinabile() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    kit.putFile("test", "Gita/Mare/2.jpg", b)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
    assertThat(visible("test")).isEmpty()
    assertThat(kit.nas.opCount(FakeNasOp.TRASH_MOVE)).isEqualTo(1)
    val entry = kit.store.allTrash().single()
    assertThat(entry.isFolder).isTrue()
    kit.run(OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, true, entry.size))))
    assertThat(visible("test")).containsExactly("Gita/1.jpg", "Gita/Mare/2.jpg")
  }

  @Test
  fun eliminaSubitoSolaDelCestinoProprioEConDoppiaConferma() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    val plan = plan(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    assertThat(plan.warnings).contains(PlanWarning.PERMANENT_DELETE)
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.filesIn("test")).isEmpty()
    assertThat(kit.store.trashEntry(entry.trashId)!!.state).isEqualTo(TrashEntryState.PURGED)

    // Una voce che nel mio diario non c'e' non si elimina: non e' mia.
    assertThat(rejection(OpRequest.PurgeNow(listOf(OwnTrashRef(PlanFixtures.id(77)))))).isEqualTo(PlanRejection.NOT_MINE)
    assertThat(rejection(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))).isEqualTo(PlanRejection.NOT_MINE)
  }

  @Test
  fun eliminaSubitoUnaCartellaCestinataSvuotaTuttoDalFondo() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    kit.putFile("test", "Gita/Mare/2.jpg", b)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
    val entry = kit.store.allTrash().single()
    kit.run(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))
    assertThat(kit.filesIn("test")).isEmpty()
    assertThat(kit.exists("test", entry.trashedPath.value)).isFalse()
  }

  @Test
  fun inUnaShareInSolaLetturaSiAgisceSoloDoveSiPuoEIlRestoSiElenca() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.putFile("test-comune", "b.jpg", b)
    kit.readOnlyShares += "test-comune"
    val plan = plan(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"), kit.itemOf("test-comune", "b.jpg"))))
    assertThat(plan.items).hasSize(1)
    assertThat(plan.skipped.single().reason).isEqualTo(SkipReason.READ_ONLY)
    assertThat(rejection(OpRequest.Trash(listOf(kit.itemOf("test-comune", "b.jpg"))))).isEqualTo(PlanRejection.READ_ONLY)
  }

  // --- File bloccati, cancello, annullamento -----------------------------------------------------------------------------------

  @Test
  fun unFileApertoDalPcAspettaERiprendeQuandoSiLibera() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.lock("test", "a.jpg")
    kit.run(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    val waiting = lastState()
    assertThat(waiting).isInstanceOf(NasOperationState.WaitingLocked::class.java)
    assertThat((waiting as NasOperationState.WaitingLocked).lockedCount).isEqualTo(1)
    assertThat(visible("test")).containsExactly("a.jpg")

    // Troppo presto: l'attesa non e' scaduta, non si tocca.
    kit.unlock("test", "a.jpg")
    assertThat(kit.operations.resumeAll(1.minutes).itemsDone).isEqualTo(0)
    kit.advance(61_000)
    val result = kit.operations.resumeAll(1.minutes)
    assertThat(result.itemsDone).isEqualTo(1)
    assertThat(visible("test")).containsExactly("b.jpg")
    assertThat(lastState()).isInstanceOf(NasOperationState.Done::class.java)
  }

  @Test
  fun dopo24OreDiBloccoLElementoSiFermaESpiegaPerche() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.lock("test", "a.jpg")
    kit.run(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    var rounds = 0
    while (lastState().active && rounds++ < 30) {
      kit.advance(LockedBackoff.DELAYS_MS.max() + 1)
      kit.operations.resumeAll(1.minutes)
    }
    val state = lastState()
    assertThat(state).isInstanceOf(NasOperationState.Failed::class.java)
    assertThat((state as NasOperationState.Failed).code).isEqualTo(OpErrorCode.LOCKED_TOO_LONG)
    assertThat(visible("test")).containsExactly("a.jpg")
  }

  @Test
  fun congelamentoEScrittureFermeBloccanoIPianiEFermanoLeOperazioniInCorso() = runTest {
    kit.putFile("test", "a.jpg", a)
    val item = kit.itemOf("test", "a.jpg")
    kit.gate.byShare["test"] = GateStatus.FROZEN
    assertThat(rejection(OpRequest.Rename(item, "b.jpg"))).isEqualTo(PlanRejection.WRITES_BLOCKED)

    kit.gate.byShare.clear()
    val plan = plan(OpRequest.Rename(item, "b.jpg"))
    kit.gate.byShare["test"] = GateStatus.WRITES_STOPPED
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isEqualTo(StartOutcome.WritesBlocked)
    assertThat(visible("test")).containsExactly("a.jpg")

    // Un'operazione gia' nel diario si ferma al blocco e riprende quando passa.
    kit.gate.byShare.clear()
    kit.lock("test", "a.jpg")
    kit.operations.start(plan, kit.confirm(plan))
    kit.unlock("test", "a.jpg")
    kit.advance(61_000)
    kit.gate.byShare["test"] = GateStatus.FROZEN
    assertThat(kit.operations.resumeAll(1.minutes).stoppedBy).isEqualTo(RunStop.BLOCKED)
    assertThat((lastState() as NasOperationState.Paused).reason).isEqualTo(PauseReason.FROZEN)
    assertThat(visible("test")).containsExactly("a.jpg")
    kit.gate.byShare.clear()
    kit.operations.resumeAll(1.minutes)
    assertThat(visible("test")).containsExactly("b.jpg")
  }

  @Test
  fun senzaOraDelNasNonSiScriveNiente() = runTest {
    kit.putFile("test", "a.jpg", a)
    val plan = plan(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    kit.clocks.nasNow = null
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isEqualTo(StartOutcome.WritesBlocked)
    assertThat(visible("test")).containsExactly("a.jpg")
  }

  @Test
  fun annullareFermaLEsecuzioneEMantieneQuelloCheEGiaFatto() = runTest {
    kit.putFile("test", "V/1.jpg", a)
    kit.putFile("test", "V/2.jpg", b)
    kit.putFile("test", "V/3.jpg", byteArrayOf(5))
    val items = listOf("V/1.jpg", "V/2.jpg", "V/3.jpg").map { kit.itemOf("test", it) }
    val plan = plan(OpRequest.Move(items, "test-comune", null))
    // La rete cade all'inizio del secondo file (copia lato server del 2°: operazione 4 dopo copia, pubblicazione e cestino del 1°).
    kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(4, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.BEFORE, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection)
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.opsFaults = null
    val paused = lastState() as NasOperationState.Paused
    assertThat(paused.reason).isEqualTo(PauseReason.NAS_UNREACHABLE)
    assertThat(paused.done).isEqualTo(1)

    kit.operations.cancel(paused.id)
    assertThat(lastState()).isInstanceOf(NasOperationState.Cancelled::class.java)
    assertThat(visible("test-comune")).containsExactly("1.jpg")
    assertThat(visible("test")).containsExactly("V/2.jpg", "V/3.jpg")
    assertThat(appFiles("test-comune")).isEmpty()
  }

  // --- Conferme e piani sbagliati ---------------------------------------------------------------------------------------------------

  @Test
  fun senzaUnaConfermaValidaNonPartePerNiente() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.putFile("test", "c.jpg", b)
    val plan = plan(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    val other = plan(OpRequest.Rename(kit.itemOf("test", "c.jpg"), "d.jpg"))

    // Conferma di un altro piano.
    assertThat(kit.operations.start(plan, kit.confirm(other))).isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.WrongPlan))
    // Conferma scaduta: il foglio e' rimasto aperto troppo.
    val stale = kit.confirm(plan)
    kit.advance(ConfirmationRules.MAX_AGE_MS + 1)
    assertThat(kit.operations.start(plan, stale)).isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.Expired))
    assertThat(visible("test")).containsExactly("a.jpg", "c.jpg")
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
  }

  @Test
  fun lostessoPianoAvviatoDueVolteEUnaSolaOperazione() = runTest {
    kit.putFile("test", "a.jpg", a)
    val plan = plan(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    val confirmation = kit.confirm(plan)
    val first = kit.operations.start(plan, confirmation) as StartOutcome.Started
    val second = kit.operations.start(plan, confirmation)
    assertThat(second).isEqualTo(StartOutcome.AlreadyStarted(first.id))
    assertThat(kit.store.ops(OpState.entries.toSet())).hasSize(1)
  }

  @Test
  fun unPianoChePuntaAlleDelleAppNonPartePerNienteConUnaConfermaValida() = runTest {
    val bad = PlanFixtures.rename(".mycloudgallery/devices/x", "b.jpg")
    assertThat(kit.operations.start(bad, kit.confirm(bad))).isEqualTo(StartOutcome.PlanInvalid(PlanViolation.APP_AREA_PATH))
    val foreignPurge = PlanFixtures.purge(owner = PlanFixtures.other)
    assertThat(kit.operations.start(foreignPurge, kit.confirm(foreignPurge))).isEqualTo(StartOutcome.PlanInvalid(PlanViolation.NOT_OWN_TRASH))
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
  }

  // --- Il diario regge -------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilDiarioRifiutaUnSaltoDiPassoEUnaVoceDelCestinoSenzaOperazione() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.run(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    val record = kit.store.items(kit.store.ops(setOf(OpState.DONE)).single().id).single()
    val skipping = record.copy(state = OpItemState.PLANNED)
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { kit.store.updateItem(skipping) } }

    val orphan = kit.store.allTrash().firstOrNull() ?: run {
      kit.putFile("test", "z.jpg", a)
      kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "z.jpg"))))
      kit.store.allTrash().single()
    }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { kit.store.recordTrash(orphan.copy(trashId = PlanFixtures.id(99), opId = null)) }
    }
  }

  @Test
  fun lePrimeScritturePerOgniOperazioneSonoSempreNelDiarioPrimaDelNas() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    var writesBeforeFirstNasCall = -1
    kit.nas.fault = FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.COPY_TO_TEMP && writesBeforeFirstNasCall < 0) writesBeforeFirstNasCall = kit.store.itemWrites
      null
    }
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))
    kit.nas.fault = null
    // Prima della copia lato server lo stato COPYING e il nome del temporaneo erano gia' scritti.
    assertThat(writesBeforeFirstNasCall).isAtLeast(1)
    assertThat(kit.store.items(kit.store.ops(setOf(OpState.DONE)).single().id).single().tempName).isNotNull()
  }
}
