package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
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
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.nasops.engine.HeavyWorkPolicy
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Gli edge case del §2.7 sull'esecutore vero (P12): E-D1–E-D13, E-N9, E-N11, E-N13, e "Elimina subito" (Q8) solo dal cestino e con doppia
 * conferma. E-D6 (spostamento interrotto) e' la prova di [EveryCallTest]; E-D8 (album condivisi) arriva con la Fase 7.
 */
class EdgeCasesTest {

  private val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
  private val a = byteArrayOf(1, 2, 3, 4)
  private val b = byteArrayOf(9, 8, 7)
  private val day = 24L * 60 * 60 * 1000

  private suspend fun plan(request: OpRequest): OpPlan = (kit.operations.plan(request) as PlanOutcome.Ready).plan

  private suspend fun rejection(request: OpRequest): PlanRejection = (kit.operations.plan(request) as PlanOutcome.Rejected).reason

  private fun visible(share: String) = kit.filesIn(share).filterNot { it.startsWith(".mycloudgallery") }

  private fun appFiles(share: String) = kit.filesIn(share).filter { it.startsWith(".mycloudgallery") }

  private fun last() = kit.operations.operations.value.last()

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  // --- E-D1: "Solo dal telefono" / "Ovunque" ----------------------------------------------------------------------------------

  @Test
  fun eD1_ovunqueToccaSoloLeCopieDelNasScelteESoloDalTelefonoNonToccaIlNas() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.putFile("test-comune", "Foto/a.jpg", a)
    // "Solo dal telefono": nessuna richiesta al NAS, nessuna scrittura (lo orchestra la UI con il cestino di Android).
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
    // "Ovunque" per la copia dell'area privata: solo quella va nel cestino del NAS, l'altra resta.
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    assertThat(visible("test")).isEmpty()
    assertThat(visible("test-comune")).containsExactly("Foto/a.jpg")
  }

  // --- E-D2: solo sul NAS: cestino del NAS per 30 giorni ----------------------------------------------------------------------

  @Test
  fun eD2_soloSulNasVaNelCestinoPer30GiorniPoiSiElimina() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    kit.trash.refresh()
    assertThat((kit.trash.items.value as NasTrashState.Ready).items.single().daysLeft).isEqualTo(30)
    kit.advance(29 * day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    kit.advance(day + 1)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(1)
    assertThat(kit.filesIn("test")).isEmpty()
  }

  // --- E-D3: ripristino con la cartella sparita ---------------------------------------------------------------------------------

  @Test
  fun eD3_ilRipristinoRicreaLaCartellaSparita() = runTest {
    kit.putFile("test", "Viaggi/2024/Mare/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Viaggi/2024/Mare/a.jpg"))))
    kit.deleteFromPc("test", "Viaggi")
    val entry = kit.store.allTrash().single()
    kit.run(OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, false, entry.size))))
    assertThat(visible("test")).containsExactly("Viaggi/2024/Mare/a.jpg")
    assertThat(kit.store.trashEntry(entry.trashId)!!.state).isEqualTo(TrashEntryState.RESTORED)
  }

  // --- E-D4: pulizia solo dal diario locale, record, ora del NAS, tetti, handle -------------------------------------------------

  @Test
  fun eD4_laPuliziaUsaSoloIlDiarioLOraDelNasEITetti() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    // Un file del cestino di un ALTRO dispositivo, scaduto da secoli: non e' nel diario, non si tocca.
    kit.putFile("test", NasLayout.trashItemFile(NasOpsTestKit.DEVICE_B, id(60)).value, b)
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    val opens = ArrayList<FakeNasOp>()
    kit.nas.fault = FakeNasFault { op, _, _ ->
      opens += op
      null
    }
    val report = kit.trash.purgeExpired(1.minutes)
    kit.nas.fault = null
    assertThat(report.purged).isEqualTo(1)
    // Una sola eliminazione con handle (deleteIfUnchanged), nessuna rinomina, nessun'altra scrittura.
    assertThat(opens.filter { it in setOf(FakeNasOp.DELETE, FakeNasOp.RENAME, FakeNasOp.TRASH_MOVE, FakeNasOp.RMDIR) }).containsExactly(FakeNasOp.DELETE)
    assertThat(kit.exists("test", entry.trashedPath.value)).isFalse()
    assertThat(kit.exists("test", NasLayout.trashItemFile(NasOpsTestKit.DEVICE_B, id(60)).value)).isTrue()
  }

  // --- E-D5: poco spazio sulla destinazione ---------------------------------------------------------------------------------------

  @Test
  fun eD5_pocoSpazioOQuotaSullaDestinazioneNonFannoIniziareNiente() = runTest {
    kit.putFile("test", "Video/a.mp4", ByteArray(10_000))
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 30, callerFreeBytes = (1L shl 30) + 9_999) }
    assertThat(rejection(OpRequest.Move(listOf(kit.itemOf("test", "Video/a.mp4")), "test-comune", null))).isEqualTo(PlanRejection.NOT_ENOUGH_SPACE)
    assertThat(kit.store.ops(OpState.entries.toSet())).isEmpty()
    // Con il margine di 1 GiB giusto parte.
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 31, callerFreeBytes = (1L shl 30) + 10_000) }
    assertThat(kit.operations.plan(OpRequest.Move(listOf(kit.itemOf("test", "Video/a.mp4")), "test-comune", null))).isInstanceOf(PlanOutcome.Ready::class.java)
  }

  // --- E-D7: eliminazione di massa: riepilogo, conferma, annullabile ------------------------------------------------------------

  @Test
  fun eD7_eliminazioneDiMassaConRiepilogoConfermaEAnnullabile() = runTest {
    repeat(300) { kit.putFile("test", "Massa/$it.jpg", byteArrayOf(it.toByte(), 1)) }
    val items = (0 until 300).map { kit.itemOf("test", "Massa/$it.jpg") }
    val plan = plan(OpRequest.Trash(items))
    assertThat(plan.summary.itemCount).isEqualTo(300)
    assertThat(plan.summary.totalBytes).isEqualTo(600)
    // Senza conferma non parte niente.
    val other = plan(OpRequest.Trash(items.take(1)))
    assertThat(kit.operations.start(plan, kit.confirm(other))).isInstanceOf(StartOutcome.ConfirmationRejected::class.java)
    assertThat(visible("test")).hasSize(300)
    kit.operations.start(plan, kit.confirm(plan))
    assertThat(visible("test")).isEmpty()
    // Annullabile finche' sono nel cestino: un ripristino di tutti li riporta.
    val refs = kit.store.allTrash().map { TrashRestoreRef("test", kit.deviceId, it.trashId, it.originalPath, false, it.size) }
    kit.run(OpRequest.Restore(refs))
    assertThat(visible("test")).hasSize(300)
  }

  // --- E-D9: nome non valido ---------------------------------------------------------------------------------------------------

  @Test
  fun eD9_unNomeNonValidoNonDiventaUnPianoEHaLaCorrezione() = runTest {
    kit.putFile("test", "a.jpg", a)
    for (bad in listOf("a:b.jpg", "x/y", "CON", "nome. ", "", "..")) {
      assertThat(rejection(OpRequest.Rename(kit.itemOf("test", "a.jpg"), bad))).isEqualTo(PlanRejection.INVALID_NAME)
      assertThat(rejection(OpRequest.CreateFolder("test", null, bad))).isEqualTo(PlanRejection.INVALID_NAME)
    }
    assertThat((NameRules.check("a:b.jpg") as NameRules.Check.Invalid).suggestion).isEqualTo("a_b.jpg")
    assertThat((NameRules.check("Vacanze. ") as NameRules.Check.Invalid).suggestion).isEqualTo("Vacanze")
    assertThat(visible("test")).containsExactly("a.jpg")
  }

  // --- E-D10: cartella intera: una sola rinomina nel cestino, ripristinabile -----------------------------------------------------

  @Test
  fun eD10_unaCartellaInteraEUnaSolaRinominaERipristinabile() = runTest {
    kit.putFile("test", "Gita/1.jpg", a)
    kit.putFile("test", "Gita/Mare/2.jpg", b)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita", folderSize = 7))))
    assertThat(kit.nas.opCount(FakeNasOp.TRASH_MOVE)).isEqualTo(1)
    val entry = kit.store.allTrash().single()
    assertThat(entry.isFolder).isTrue()
    assertThat(entry.fileCount).isEqualTo(2)
    kit.trash.refresh()
    val shown = (kit.trash.items.value as NasTrashState.Ready).items.single()
    assertThat(shown.isFolder).isTrue()
    assertThat(shown.fileCount).isEqualTo(2)
    kit.run(OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, true, entry.size))))
    assertThat(visible("test")).containsExactly("Gita/1.jpg", "Gita/Mare/2.jpg")
  }

  // --- E-D11: "Ovunque" con copie in aree senza permesso ----------------------------------------------------------------------

  @Test
  fun eD11_agisceSoloDoveSiPuoEMostraLElencoDelResto() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.putFile("test-comune", "a.jpg", a)
    kit.readOnlyShares += "test-comune"
    val plan = plan(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"), kit.itemOf("test-comune", "a.jpg"))))
    assertThat(plan.items.map { it.srcShare }).containsExactly("test")
    assertThat(plan.skipped.single().reason).isEqualTo(SkipReason.READ_ONLY)
    assertThat(plan.warnings).contains(PlanWarning.SOME_SKIPPED)
    kit.operations.start(plan, kit.confirm(plan))
    assertThat(visible("test-comune")).containsExactly("a.jpg")
  }

  // --- E-D12: copia lato server rifiutata --------------------------------------------------------------------------------------

  @Test
  fun eD12_copiaLatoServerRifiutataRipiegaDalTelefonoSoloQuandoLeAttivitaPesantiSonoAmmesse() = runTest {
    kit.nas.serverCopyMode = ServerCopyMode.NOT_SUPPORTED
    kit.putFile("test", "V/a.jpg", a)
    var allowed = false
    val engine = NasOpsEngine(
      kit.deviceId, kit.accountId, kit.fileOps, kit.nas.reader, kit.store, kit.clocks, kit.gate,
      heavy = HeavyWorkPolicy { allowed }, lock = NasOpsLock(),
    )
    val plan = (engine.plan(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null)) as PlanOutcome.Ready).plan
    engine.start(plan, kit.confirm(plan))
    val paused = engine.operations.value.single() as NasOperationState.Paused
    assertThat(paused.reason).isEqualTo(PauseReason.CONDITIONS)
    assertThat(visible("test-comune")).isEmpty()
    assertThat(appFiles("test-comune")).isEmpty()
    allowed = true
    engine.resumeAll(1.minutes)
    assertThat(visible("test-comune")).containsExactly("a.jpg")
    assertThat(kit.bytes("test-comune", "a.jpg")).isEqualTo(a)
    assertThat(kit.nas.opCount(FakeNasOp.OPEN_TEMP)).isEqualTo(1)
  }

  // --- E-D13: sorgente cambiata durante la copia o dopo la verifica ------------------------------------------------------------

  @Test
  fun eD13_sorgenteCambiataDuranteLaCopiaFermaLElementoSenzaCestino() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))
    var changed = false
    kit.nas.fault = FakeNasFault { op, share, _ ->
      if (op == FakeNasOp.COPY_TO_TEMP && share == "test" && !changed) {
        changed = true
        kit.modify("test", "Viaggi/a.jpg", byteArrayOf(5, 5, 5))
      }
      null
    }
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.fault = null
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_CHANGED)
    assertThat(kit.bytes("test", "Viaggi/a.jpg")).isEqualTo(byteArrayOf(5, 5, 5))
    assertThat(visible("test-comune")).isEmpty()
    assertThat(kit.store.allTrash()).isEmpty()
    assertThat(appFiles("test-comune")).isEmpty()
  }

  @Test
  fun eD13_sorgenteCambiataDopoLaVerificaRestanoEntrambeLeCopieENienteNelCestino() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))
    var changed = false
    kit.nas.fault = FakeNasFault { op, _, _ ->
      // Il PC salva il file proprio fra la pubblicazione della copia e il cestino della sorgente.
      if (op == FakeNasOp.TRASH_MOVE && !changed) {
        changed = true
        kit.modify("test", "Viaggi/a.jpg", byteArrayOf(6, 6))
      }
      null
    }
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.fault = null
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_CHANGED)
    assertThat(kit.bytes("test", "Viaggi/a.jpg")).isEqualTo(byteArrayOf(6, 6))
    assertThat(kit.bytes("test-comune", "a.jpg")).isEqualTo(a)
    // La voce del diario e' segnata: non fara' mai eliminare il file (che non e' nel cestino).
    assertThat(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.PURGED)
  }

  @Test
  fun unaCopiaRovinataNonPubblicaNienteENonCestinaLaSorgente() = runTest {
    kit.putFile("test", "Viaggi/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", null))
    var corrupted = false
    kit.nas.fault = FakeNasFault { op, share, path ->
      // Il temporaneo copiato viene alterato (stessa dimensione) prima della verifica completa.
      if (op == FakeNasOp.OPEN && share == "test-comune" && path?.value?.contains("/tmp/") == true && !corrupted) {
        corrupted = true
        kit.modify("test-comune", path.value, byteArrayOf(1, 2, 3, 9))
      }
      null
    }
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.fault = null
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.VERIFY_FAILED)
    assertThat(visible("test")).containsExactly("Viaggi/a.jpg")
    assertThat(visible("test-comune")).isEmpty()
    assertThat(appFiles("test-comune")).isEmpty()
    assertThat(kit.store.allTrash()).isEmpty()
  }

  // --- E-N9: cartella in sola lettura -------------------------------------------------------------------------------------------

  @Test
  fun eN9_inSolaLetturaAzioniSpenteConLaSpiegazione() = runTest {
    kit.putFile("test-comune", "Foto/a.jpg", a)
    kit.readOnlyShares += "test-comune"
    val item = kit.itemOf("test-comune", "Foto/a.jpg")
    assertThat(rejection(OpRequest.Rename(item, "b.jpg"))).isEqualTo(PlanRejection.READ_ONLY)
    assertThat(rejection(OpRequest.Trash(listOf(item)))).isEqualTo(PlanRejection.READ_ONLY)
    assertThat(rejection(OpRequest.CreateFolder("test-comune", null, "Nuova"))).isEqualTo(PlanRejection.READ_ONLY)
    assertThat(rejection(OpRequest.Move(listOf(kit.itemOf("test-comune", "Foto/a.jpg")), "test-comune", SharePath.parse("Altro")))).isEqualTo(PlanRejection.READ_ONLY)
    val listing = kit.folders.observe("test-comune", SharePath.parse("Foto")).first { it !is NasFolderState.Loading } as NasFolderState.Listing
    assertThat(listing.readOnly).isTrue()
    // E se la sola lettura la dice il NAS a operazione avviata: l'elemento si ferma con il permesso negato, niente cambia.
    kit.readOnlyShares.clear()
    val plan = plan(OpRequest.Rename(kit.itemOf("test-comune", "Foto/a.jpg"), "b.jpg"))
    kit.nas.setReadOnly("test-comune", true)
    kit.operations.start(plan, kit.confirm(plan))
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.PERMISSION_DENIED)
    assertThat(visible("test-comune")).containsExactly("Foto/a.jpg")
  }

  // --- E-N11: due dispositivi sullo stesso file ---------------------------------------------------------------------------------

  @Test
  fun eN11_ilSecondoDispositivoTrovaLaSorgenteSpostataOCambiataELoDice() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.putFile("test", "Foto/b.jpg", b)
    val moved = plan(OpRequest.Rename(kit.itemOf("test", "Foto/a.jpg"), "x.jpg"))
    val changed = plan(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/b.jpg"))))
    // Un altro dispositivo (qui: il PC) sposta a.jpg altrove e modifica b.jpg prima che questo esegua.
    kit.rename("test", "Foto/a.jpg", "Altrove/a.jpg")
    kit.modify("test", "Foto/b.jpg", byteArrayOf(4, 4))
    kit.operations.start(moved, kit.confirm(moved))
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_MISSING)
    kit.operations.start(changed, kit.confirm(changed))
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_CHANGED)
    assertThat(visible("test")).containsExactly("Altrove/a.jpg", "Foto/b.jpg")
    // La voce del cestino della seconda e' chiusa: niente da eliminare.
    assertThat(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.PURGED)
  }

  @Test
  fun eN11_dueDispositiviCheCestinanoLoStessoFileUnoSoloLoFaLAltroTrovaSourceMissing() = runTest {
    val other = NasOpsTestKit(engine = KitEngine.PRODUCTION, deviceId = NasOpsTestKit.DEVICE_B)
    kit.putFile("test", "Foto/a.jpg", a)
    val item = kit.itemOf("test", "Foto/a.jpg")
    val first = plan(OpRequest.Trash(listOf(item)))
    val secondEngine = NasOpsEngine(other.deviceId, other.accountId, kit.nas.fileOps(other.deviceId), kit.nas.reader, other.store, kit.clocks, kit.gate)
    val second = (secondEngine.plan(OpRequest.Trash(listOf(item))) as PlanOutcome.Ready).plan
    kit.operations.start(first, kit.confirm(first))
    secondEngine.start(second, kit.confirm(second))
    assertThat((secondEngine.operations.value.single() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_MISSING)
    // Il file e' una volta sola, nel cestino del primo; il secondo non ha voci attive.
    assertThat(kit.filesIn("test").filter { it.contains("/files/") }).hasSize(1)
    assertThat(other.store.allTrash().single().state).isEqualTo(TrashEntryState.PURGED)
  }

  // --- E-N13: file bloccato e quota ----------------------------------------------------------------------------------------------

  @Test
  fun eN13_unFileApertoDalPcAspettaConAtteseCrescentiPoiSiFerma() = runTest {
    kit.putFile("test", "a.jpg", a)
    kit.lock("test", "a.jpg")
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"))))
    val waiting = last() as NasOperationState.WaitingLocked
    assertThat(waiting.lockedCount).isEqualTo(1)
    val item = kit.store.items(waiting.id).single()
    assertThat(item.state).isEqualTo(OpItemState.RECORDED)
    assertThat(item.errorCode).isEqualTo(OpErrorCode.LOCKED)
    var rounds = 0
    while (last().active && rounds++ < 40) {
      kit.advance(12 * 60 * 60 * 1000L + 1)
      kit.operations.resumeAll(1.minutes)
    }
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.LOCKED_TOO_LONG)
    assertThat(visible("test")).containsExactly("a.jpg")
    // La voce del cestino e' chiusa (il file non e' mai entrato nel cestino): la pulizia non elimina niente di vivo.
    assertThat(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.PURGED)
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + 1)
    kit.unlock("test", "a.jpg")
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(visible("test")).containsExactly("a.jpg")
  }

  @Test
  fun eN13_laQuotaPienaHaIlSuoMessaggioENonLasciaTemporanei() = runTest {
    kit.putFile("test", "V/a.jpg", ByteArray(100))
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    kit.nas.spaceOf = { NasSpaceInfo(totalBytes = 1_000, freeBytes = 900, callerFreeBytes = 10) }
    kit.operations.start(plan, kit.confirm(plan))
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.QUOTA)
    assertThat(appFiles("test-comune")).isEmpty()
    assertThat(visible("test")).containsExactly("V/a.jpg")
    val space = kit.space.load("test-comune") as NasSpaceState.Ready
    assertThat(space.details.quotaBytes).isEqualTo(10)
  }

  // --- Q8: "Elimina subito" solo dal cestino e con doppia conferma --------------------------------------------------------------

  @Test
  fun q8_eliminaSubitoVuoleDueTocchiESoloVociDelProprioCestino() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    val plan = plan(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    assertThat(plan.warnings).contains(PlanWarning.PERMANENT_DELETE)
    // Un tocco solo non basta: il foglio non da' la conferma, e una conferma "a un tocco" e' un errore di programmazione.
    val sheet = ConfirmationSheet(plan)
    assertThat(sheet.tap(kit.clocks.monotonicMs())).isNull()
    assertThat(sheet.tapsLeft).isEqualTo(1)
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ConfirmationIssuer.issue(plan, 1, kit.clocks.monotonicMs()) }
    // R6: il secondo tocco e' un secondo gesto (non il secondo colpo di un doppio tocco).
    kit.clocks.monotonic += dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules.MIN_TAP_GAP_MS
    val confirmation = sheet.tap(kit.clocks.monotonicMs())!!
    // Un piano di "elimina subito" fuori dal proprio cestino non parte nemmeno con due tocchi.
    val media = OpPlan(
      plan.kind,
      listOf(plan.items.single().copy(srcPath = SharePath.parse("Foto/b.jpg"))),
      emptyList(),
      plan.summary,
      plan.warnings,
    )
    assertThat(kit.operations.start(media, ConfirmationIssuer.issue(media, 2, kit.clocks.monotonicMs()))).isEqualTo(StartOutcome.PlanInvalid(PlanViolation.NOT_A_TRASH_ITEM))
    assertThat(kit.operations.start(plan, confirmation)).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.filesIn("test")).isEmpty()
  }

  @Test
  fun q8_unaVoceRipristinataNelFrattempoNonSiEliminaPiu() = runTest {
    kit.putFile("test", "Foto/a.jpg", a)
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    val purge = plan(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))
    kit.run(OpRequest.Restore(listOf(TrashRestoreRef("test", kit.deviceId, entry.trashId, entry.originalPath, false, entry.size))))
    kit.operations.start(purge, kit.confirm(purge))
    assertThat((last() as NasOperationState.Failed).code).isEqualTo(OpErrorCode.SOURCE_MISSING)
    assertThat(visible("test")).containsExactly("Foto/a.jpg")
  }

  // --- "Annulla" (§2.7) e i due difetti trovati da P15 sull'esecutore di riferimento ------------------------------------------

  @Test
  fun annullaConIlTemporaneoNonTogliibileNonAnnullaENonLasciaTemporaneiNonCitati() = runTest {
    kit.putFile("test", "V/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    // La rete cade alla pubblicazione: l'elemento resta VERIFIED con il temporaneo copiato e verificato.
    kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.BEFORE, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection, op = dev.pampa.mycloudgallery.core.nas.testing.SimOp.RENAME)
    kit.operations.start(plan, kit.confirm(plan))
    // "Annulla" mentre la rete e' ancora giu' per la cancellazione del temporaneo: niente si annulla (P15, D2).
    kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.BEFORE, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection, op = dev.pampa.mycloudgallery.core.nas.testing.SimOp.DELETE_ON_CLOSE)
    val id = kit.store.ops(OpState.entries.toSet()).single().id
    kit.operations.cancel(id)
    kit.nas.opsFaults = null
    val item = kit.store.items(id).single()
    assertThat(item.state).isEqualTo(OpItemState.VERIFIED)
    assertThat(appFiles("test-comune")).containsExactly(item.tempName!!.value)
    assertThat((last() as NasOperationState.Paused).reason).isEqualTo(PauseReason.NAS_UNREACHABLE)
    // Di nuovo, con la rete: annullato, temporaneo tolto, sorgente al suo posto.
    kit.operations.cancel(id)
    assertThat(kit.store.items(id).single().state).isEqualTo(OpItemState.CANCELLED)
    assertThat(last()).isInstanceOf(NasOperationState.Cancelled::class.java)
    assertThat(appFiles("test-comune")).isEmpty()
    assertThat(visible("test")).containsExactly("V/a.jpg")
  }

  @Test
  fun annullaDopoUnaPubblicazioneConLaRispostaPersaLaPortaATermineSenzaDoppioni() = runTest {
    kit.putFile("test", "V/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    // La pubblicazione avviene, la risposta no: l'elemento resta VERIFIED ma la copia e' gia' visibile (P15, D3).
    kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.AFTER, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection, op = dev.pampa.mycloudgallery.core.nas.testing.SimOp.RENAME)
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.opsFaults = null
    val id = kit.store.ops(OpState.entries.toSet()).single().id
    assertThat(kit.store.items(id).single().state).isEqualTo(OpItemState.VERIFIED)
    assertThat(visible("test-comune")).containsExactly("a.jpg")
    kit.operations.cancel(id)
    // Non si annulla: si porta a termine (sorgente nel cestino), una sola copia visibile.
    assertThat(kit.store.items(id).single().state).isEqualTo(OpItemState.DONE)
    assertThat(visible("test-comune")).containsExactly("a.jpg")
    assertThat(visible("test")).isEmpty()
    assertThat(kit.store.allTrash().single().state).isEqualTo(TrashEntryState.TRASHED)
  }

  @Test
  fun unAnnullaPersoConLaMorteDelProcessoDopoAverToltoIlTemporaneoRisultaAnnullatoAllaRipresa() = runTest {
    // Banco di P15 (giro 1943): "Ferma" toglie il temporaneo, il processo muore prima di scrivere CANCELLED. Alla ripresa
    // l'elemento (COPIED o VERIFIED) non trova piu' il temporaneo: annullato, non fallito; sorgente al suo posto, nessun doppione.
    for (fault in listOf(dev.pampa.mycloudgallery.core.nas.testing.SimOp.OPEN_READ, dev.pampa.mycloudgallery.core.nas.testing.SimOp.RENAME)) {
      val k = NasOpsTestKit(engine = KitEngine.PRODUCTION)
      k.putFile("test", "V/a.jpg", a)
      val plan = (k.operations.plan(OpRequest.Move(listOf(k.itemOf("test", "V/a.jpg")), "test-comune", null)) as PlanOutcome.Ready).plan
      val faults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
        .at(if (fault == dev.pampa.mycloudgallery.core.nas.testing.SimOp.OPEN_READ) 2 else 1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.BEFORE, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection, op = fault)
      if (fault == dev.pampa.mycloudgallery.core.nas.testing.SimOp.OPEN_READ) k.nas.readFaults = faults else k.nas.opsFaults = faults
      k.operations.start(plan, k.confirm(plan))
      k.nas.readFaults = null
      k.nas.opsFaults = null
      val id = k.store.ops(OpState.entries.toSet()).single().id
      val item = k.store.items(id).single()
      assertThat(item.state).isEqualTo(if (fault == dev.pampa.mycloudgallery.core.nas.testing.SimOp.OPEN_READ) OpItemState.COPIED else OpItemState.VERIFIED)
      // Il temporaneo sparisce (lo ha tolto il "Ferma" perso), poi il processo riparte dal diario.
      k.deleteFromPc("test-comune", item.tempName!!.value)
      k.restart()
      k.operations.resumeAll(1.minutes)
      assertThat(k.store.items(id).single().state).isEqualTo(OpItemState.CANCELLED)
      assertThat(k.store.ops(OpState.entries.toSet()).single().state).isEqualTo(OpState.CANCELLED)
      assertThat(k.filesIn("test")).containsExactly("V/a.jpg")
      assertThat(k.filesIn("test-comune")).isEmpty()
      assertThat(k.store.allTrash()).isEmpty()
    }
  }

  @Test
  fun unFileDiversoAlNomeDiDestinazioneConIlTemporaneoSparitoFermaLElemento() = runTest {
    kit.putFile("test", "V/a.jpg", a)
    val plan = plan(OpRequest.Move(listOf(kit.itemOf("test", "V/a.jpg")), "test-comune", null))
    kit.nas.opsFaults = dev.pampa.mycloudgallery.core.nas.testing.FaultPlan()
      .at(1, dev.pampa.mycloudgallery.core.nas.testing.FaultPhase.AFTER, dev.pampa.mycloudgallery.core.nas.testing.SimFault.DropConnection, op = dev.pampa.mycloudgallery.core.nas.testing.SimOp.RENAME)
    kit.operations.start(plan, kit.confirm(plan))
    kit.nas.opsFaults = null
    val id = kit.store.ops(OpState.entries.toSet()).single().id
    // Pubblicata con la risposta persa, poi cambiata dal PC prima della ripresa: non e' piu' "la nostra" copia.
    kit.modify("test-comune", "a.jpg", b)
    kit.operations.resumeAll(1.minutes)
    val item = kit.store.items(id).single()
    assertThat(item.state).isEqualTo(OpItemState.FAILED)
    assertThat(item.errorCode).isEqualTo(OpErrorCode.UNEXPECTED)
    // Niente nel cestino: la sorgente resta dov'e'.
    assertThat(visible("test")).containsExactly("V/a.jpg")
    assertThat(kit.store.allTrash()).isEmpty()
  }

  @Test
  fun unPianoUgualeAUnoGiaChiusoConUnaConfermaNuovaEUnaRichiestaNuova() = runTest {
    // P15, O1: rinomina a→b, b→a, di nuovo a→b. Il terzo piano e' identico al primo (stessa identita' del file).
    kit.putFile("test", "a.jpg", a)
    val first = plan(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    kit.operations.start(first, kit.confirm(first))
    kit.advance(1_000)
    kit.run(OpRequest.Rename(kit.itemOf("test", "b.jpg"), "a.jpg"))
    kit.advance(1_000)
    val third = plan(OpRequest.Rename(kit.itemOf("test", "a.jpg"), "b.jpg"))
    assertThat(third.hash).isEqualTo(first.hash)
    val confirmation = kit.confirm(third)
    assertThat(kit.operations.start(third, confirmation)).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(visible("test")).containsExactly("b.jpg")
    // Lo stesso tocco ripetuto (la stessa conferma) non fa partire niente di nuovo.
    assertThat(kit.operations.start(third, confirmation)).isInstanceOf(StartOutcome.AlreadyStarted::class.java)
    assertThat(kit.store.ops(OpState.entries.toSet())).hasSize(3)
  }

  @Test
  fun unaConfermaVecchiaODiUnAltroPianoNonFaPartireNiente() = runTest {
    kit.putFile("test", "a.jpg", a)
    val plan = plan(OpRequest.Trash(listOf(kit.itemOf("test", "a.jpg"))))
    val stale = kit.confirm(plan)
    kit.advance(11 * 60 * 1000L)
    assertThat(kit.operations.start(plan, stale)).isEqualTo(StartOutcome.ConfirmationRejected(ConfirmationCheck.Expired))
    assertThat(visible("test")).containsExactly("a.jpg")
    assertThat(kit.folders.observe("test", null).first()).isNotNull()
  }
}
