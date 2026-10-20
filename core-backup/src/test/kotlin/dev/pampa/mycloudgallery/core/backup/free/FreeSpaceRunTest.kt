package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P9, il giro di "Libera spazio" dopo il piano (§2.5 punti 2-7, D21, D31, R6 della 4B): lotti di al massimo 2.000, "Libera subito" solo con due
 * gesti distinti, la conferma legata al piano per 10 minuti, la verifica che vale 6 ore di orologio monotono e solo nello stesso avvio del
 * telefono, il controllo finale prima di ogni dialogo, le risposte di Android (confermato, rifiutato, in parte, sconosciuto), E-B22 a giro
 * iniziato, D13 nei lotti.
 */
class FreeSpaceRunTest {

  private val bench = FreeSpaceBench()

  @Test
  fun lottiDiAlMassimoDuemila_tuttiControllati_eTuttiLiberati() = runTest {
    val count = 4_500
    repeat(count) { bench.safe(size = 600) }
    val run = bench.start()
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(count)
    assertThat(run.batchesTotal).isEqualTo(3)
    val done = bench.freeAll(run)
    assertThat(bench.presented.map { it.items.size }).containsExactly(2_000, 2_000, 500).inOrder()
    assertThat(bench.presented.map { it.index to it.of }).containsExactly(1 to 3, 2 to 3, 3 to 3).inOrder()
    assertThat(bench.presented.all { it.mode == TrashMode.ANDROID_TRASH }).isTrue()
    assertThat(done.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(done.freedItems).isEqualTo(count)
    assertThat(done.freedBytes).isEqualTo(count * 600L)
    assertThat(done.batchesDone).isEqualTo(3)
    // Il catalogo lo sa subito (la Libreria mostra "solo NAS"), e il diario lo sapeva gia' prima.
    assertThat(bench.catalog.freed).hasSize(count)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  @Test
  fun liberaSubito_soloConDueGestiDistinti_eIlDialogoDefinitivo() = runTest {
    bench.safe()
    bench.safe()
    val now = bench.space.plan(FreeSpaceFilter(), permanent = true)
    val trash = bench.space.plan(FreeSpaceFilter())

    // Il foglio: un doppio tocco (sotto i 600 ms) conta una volta sola; serve un secondo gesto dopo aver letto il pannello nuovo.
    val sheet = PhoneConfirmationSheet(now, shownAtMonotonicMs = bench.clock.monotonicMs())
    bench.clock.monotonic += 700
    assertThat(sheet.tap(bench.clock.monotonicMs())).isNull()
    bench.clock.monotonic += 150
    assertThat(sheet.tap(bench.clock.monotonicMs())).isNull()
    assertThat(sheet.tapsLeft).isEqualTo(1)
    bench.clock.monotonic += 650
    val twoGestures = sheet.tap(bench.clock.monotonicMs())!!
    assertThat(twoGestures.confirmedSteps).isEqualTo(2)

    // La conferma del cestino (un tocco) non vale per "Libera subito": e' un altro piano.
    val oneTap = TestConfirmations.confirm(trash, bench.clock.monotonicMs())
    val rejected = bench.space.verify(now, oneTap, 1.hours)
    assertThat(rejected.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(rejected.blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)
    assertThat(bench.removed).isEmpty()

    // Con i due gesti: il giro e' definitivo e il lotto chiede il dialogo di eliminazione (createDeleteRequest).
    val run = bench.space.verify(now, twoGestures, 1.hours)
    assertThat(run.permanent).isTrue()
    val batch = bench.space.nextBatch(run)!!
    assertThat(batch.mode).isEqualTo(TrashMode.DELETE_NOW)
    assertThat(bench.batchViolations(batch)).isEmpty()
    bench.space.recordOutcome(batch, bench.dialog(batch))
    assertThat(bench.removed).hasSize(2)
  }

  @Test
  fun laConfermaValePerQuestoPianoEPerDieciMinuti() = runTest {
    bench.safe()
    val (plan, confirmation) = bench.confirmed()
    // Dieci minuti e un millisecondo dopo: scaduta.
    bench.clock.monotonic += PhoneConfirmationRules.MAX_AGE_MS + 1
    assertThat(bench.space.verify(plan, confirmation, 1.hours).blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)

    // LS-13 della verifica funzionale 5B: un media al sicuro in piu' dopo il foglio non rifiuta piu' la conferma, ma **non entra** nel giro
    // (la lista non cresce mai); una copia con un timbro diverso (il PC l'ha toccata dopo il foglio) esce. Lo provano `Correzioni5BLiberaSpazioTest`
    // e il caso "arrivato dopo il foglio" di `FreeSpaceEveryCallTest`. Qui: un piano di un altro filtro non vale.
    val (plan2, confirmation2) = bench.confirmed()
    val other = FreeSpaceFilter(kind = dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind.VIDEOS_ONLY)
    val otherPlan = bench.space.plan(other)
    assertThat(bench.space.verify(otherPlan, confirmation2, 1.hours).blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)
    assertThat(plan2.hash).isNotEqualTo(otherPlan.hash)

    // Una conferma da un istante monotono nel futuro non e' di questo avvio del telefono.
    val (plan4, _) = bench.confirmed()
    val future = TestConfirmations.confirm(plan4, bench.clock.monotonicMs() + 60_000)
    assertThat(bench.space.verify(plan4, future, 1.hours).blocked).isEqualTo(FreeSpaceBlock.CONFIRMATION_REJECTED)
    assertThat(bench.removed).isEmpty()
  }

  @Test
  fun laVerificaValeSeiOre_dalLInizioDelGiro() = runTest {
    repeat(2) { bench.safe() }
    val run = bench.start()
    assertThat(run.validUntilMonotonicMs).isEqualTo(bench.clock.monotonicMs() + FreeSpaceRules.VERIFICATION_VALID_MS)
    bench.clock.monotonic += FreeSpaceRules.VERIFICATION_VALID_MS
    // Esattamente 6 ore: vale ancora.
    val batch = bench.space.nextBatch(run)
    assertThat(batch).isNotNull()
    bench.space.recordOutcome(batch!!, TrashOutcome.Refused)

    val run2 = bench.start()
    bench.clock.monotonic += FreeSpaceRules.VERIFICATION_VALID_MS + 1
    assertThat(bench.space.nextBatch(run2)).isNull()
    assertThat(bench.refresh(run2).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(bench.removed).isEmpty()
  }

  @Test
  fun unRiavvioAnnullaLaVerifica_ancheSeLOrologioMonotonoSembraBuono() = runTest {
    repeat(2) { bench.safe() }
    val run = bench.start()
    // Il telefono si riavvia: il processo muore, il monotono riparte da capo e il conteggio dei riavvii sale.
    bench.restart()
    bench.bootCount = bench.bootCount!! + 1
    bench.clock.monotonic = 5_000
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.refresh(run).state).isEqualTo(FreeSpaceRunState.EXPIRED)

    // Anche con il monotono "avanti" rispetto all'inizio del giro (dopo un riavvio ci arriva in fretta): conta il conteggio dei riavvii.
    val run2 = bench.start()
    bench.restart()
    bench.bootCount = bench.bootCount!! + 1
    bench.clock.monotonic += 60_000
    assertThat(bench.space.nextBatch(run2)).isNull()
    assertThat(bench.refresh(run2).state).isEqualTo(FreeSpaceRunState.EXPIRED)

    // Il monotono che torna indietro (un riavvio senza conteggio): scaduto.
    val run3 = bench.start()
    bench.clock.monotonic -= 1
    assertThat(bench.space.nextBatch(run3)).isNull()
    assertThat(bench.removed).isEmpty()
  }

  @Test
  fun stessoAvvio_unProcessoNuovoRiprendeIlGiro_senzaConteggioDeiRiavviiNo() = runTest {
    repeat(3) { bench.safe() }
    val run = bench.start(budget = Duration.ZERO)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    // Il processo muore a meta' giro (stesso avvio del telefono, conteggio dei riavvii noto): la ripresa continua da dove era.
    bench.restart()
    bench.clock.monotonic += 60_000
    val resumed = bench.space.resume(run, 1.hours)
    assertThat(resumed.state).isEqualTo(FreeSpaceRunState.READY)
    bench.freeAll(resumed)
    assertThat(bench.removed).hasSize(3)

    // Senza il conteggio dei riavvii la verifica vale solo nello stesso processo: dopo una morte del processo il giro e' scaduto.
    bench.bootCount = null
    repeat(2) { bench.safe() }
    val run2 = bench.start(budget = Duration.ZERO)
    bench.restart()
    assertThat(bench.space.resume(run2, 1.hours).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    // Nello stesso processo invece va.
    val run3 = bench.start()
    assertThat(run3.state).isEqualTo(FreeSpaceRunState.READY)
    bench.freeAll(run3)
    assertThat(bench.removed).hasSize(5)
  }

  @Test
  fun leRisposteDiAndroid_rifiutato_inParte_sconosciuto() = runTest {
    // Rifiutato: niente cambia e nessun altro lotto viene proposto.
    repeat(3) { bench.safe() }
    val run = bench.start()
    val batch = bench.space.nextBatch(run)!!
    val refused = bench.space.recordOutcome(batch, bench.dialog(batch, accept = false))
    assertThat(refused.state).isEqualTo(FreeSpaceRunState.CANCELLED)
    assertThat(bench.space.nextBatch(refused)).isNull()
    assertThat(bench.removed).isEmpty()

    // In parte: solo le righe confermate sono liberate; una riga confermata che non era nel lotto non conta.
    val run2 = bench.start()
    val batch2 = bench.space.nextBatch(run2)!!
    val one = batch2.items.first().phone
    bench.dialog(batch2, only = setOf(one))
    val stranger = dev.pampa.mycloudgallery.core.backup.contract.PhoneRef("external_primary", 99_999, bench.phone.version)
    val partial = bench.space.recordOutcome(batch2, TrashOutcome.Confirmed(setOf(one, stranger)))
    assertThat(partial.freedItems).isEqualTo(1)
    assertThat(partial.state).isEqualTo(FreeSpaceRunState.DONE)
    // La stessa risposta ripetuta non conta due volte.
    assertThat(bench.space.recordOutcome(batch2, TrashOutcome.Confirmed(setOf(one))).freedItems).isEqualTo(1)

    // Sconosciuto (l'app non ha ricevuto la risposta): la prova del telefono decide, riga per riga.
    val run3 = bench.start()
    val batch3 = bench.space.nextBatch(run3)!!
    assertThat(batch3.items).hasSize(2)
    bench.dialog(batch3, only = setOf(batch3.items.first().phone))
    val unknown = bench.space.recordOutcome(batch3, TrashOutcome.Unknown)
    assertThat(unknown.freedItems).isEqualTo(1)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  @Test
  fun lAppUccisaDuranteIlDialogo_laRipresaNonPerdeLaTraccia() = runTest {
    repeat(4) { bench.safe() }
    val run = bench.start()
    val batch = bench.space.nextBatch(run)!!
    // L'utente conferma nel dialogo di sistema, ma il processo e' morto: la risposta non arriva.
    bench.dialog(batch, only = batch.items.take(3).map { it.phone }.toSet())
    bench.restart()
    assertThat(bench.trackingViolations(settled = false)).isEmpty()
    // La ripresa risolve il lotto con la prova del telefono: tre liberate, una rimasta (mai riproposta da quel lotto).
    val resumed = bench.space.resume(run, 1.hours)
    assertThat(resumed.freedItems).isEqualTo(3)
    assertThat(resumed.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
    assertThat(bench.catalog.freed).hasSize(3)
  }

  @Test
  fun unaRigaCheIlTelefonoNonSaDire_restaInDubbio_finoAllaLetturaDelTelefono() = runTest {
    repeat(2) { bench.safe() }
    val run = bench.start()
    val batch = bench.space.nextBatch(run)!!
    // L'utente conferma solo una delle due righe (o il sistema ne toglie una sola), poi il processo muore.
    bench.dialog(batch, only = setOf(batch.items.first().phone))
    // Il MediaStore si ricostruisce (E-P22): le righe non si riconoscono piu' dall'_ID.
    bench.phone.rebuild("v2")
    bench.restart()
    val resumed = bench.space.resume(run, 1.hours)
    assertThat(resumed.freedItems).isEqualTo(0)
    // Nessuna delle due e' data per liberata: quella tolta e quella rimasta sono entrambe "in dubbio".
    assertThat(bench.diary.everything().map { it.second.state }).containsExactly(FreeItemState.IN_DOUBT, FreeItemState.IN_DOUBT)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
    // Un esito sconosciuto non e' mai "liberato" senza prova; quando la prova arriva, settle() chiude.
    assertThat(bench.space.settle()).isEqualTo(0)
  }

  @Test
  fun eB22_ilCongelamentoAGiroIniziatoFermaTutto() = runTest {
    repeat(3) { bench.safe() }
    // Al via: confermato con l'area aperta, congelata prima della verifica. Il giro non parte.
    val (plan, confirmation) = bench.confirmed()
    bench.gate.default = BackupGateState.FROZEN
    assertThat(bench.space.verify(plan, confirmation, 1.hours).blocked).isEqualTo(FreeSpaceBlock.AREA_FROZEN)

    // Fra la verifica e i lotti: nessun lotto, il giro e' fermo con il motivo.
    bench.gate.default = BackupGateState.OPEN
    val run = bench.start()
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    bench.gate.default = BackupGateState.WRITES_STOPPED
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.refresh(run).blocked).isEqualTo(FreeSpaceBlock.AREA_FROZEN)
    assertThat(bench.removed).isEmpty()
  }

  @Test
  fun dopoLaVerifica_unaModificaSulTelefono_unaVersioneNuova_unCaricamento_unProblema_esconoTutti() = runTest {
    val edited = bench.safe()
    val newVersion = bench.safe()
    val uploading = bench.safe()
    val problem = bench.safe()
    val ok = bench.safe()
    val run = bench.start()
    assertThat(run.readyItems).isEqualTo(5)
    // Samsung "Salva" riscrive il file del telefono (L38): quei byte sono una versione nuova, non al sicuro.
    bench.phone.rewrite(edited.phoneId!!, NasContent.of(bench.bytes(4_096, 777)), modifiedMs = 9_999)
    // La lettura del telefono ha visto una versione nuova del media: la nuova attuale non ha una copia verificata.
    bench.catalog.change(newVersion.id) { media ->
      val data = bench.bytes(2_000, 4242)
      val v = VersionId.random()
      media.copy(
        currentVersionId = v,
        versions = media.versions + MediaVersion(v, VersionKind.EDITED, media.currentVersionId, Fingerprint(2_000, QuickHashAlgorithm.ofContent(data), Sha256.of(data)), 5),
      )
    }
    bench.store.create(
      listOf(NewBackupItem(UploadKind.NEW_COPY, uploading.id, uploading.current, 4_096, uploading.phoneRef, null, FreeSpaceBench.PRIVATE, SharePath.parse("Backup/x.jpg"))),
    )
    bench.catalog.setProblem(problem.id, dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode.UNREADABLE)
    val done = bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(ok.phoneId)
    assertThat(done.dropped).containsExactly(
      FreeSpaceExclusion.NOT_VERIFIED, 1,
      FreeSpaceExclusion.VERSION_NOT_SAFE, 1,
      FreeSpaceExclusion.IN_PROGRESS_OR_ERROR, 2,
    )
  }

  @Test
  fun d13_neiLottiMaiUnaRigaFuoriDaMcgProva() = runTest {
    bench.fence = FreeSpacePlanTest.TestFence
    bench.restart()
    val inside = (1..3).map { bench.safe(folder = "DCIM/mcg-prova/") }
    val outside = bench.safe(folder = "DCIM/Camera/")
    val moved = bench.safe(folder = "DCIM/mcg-prova/")
    val run = bench.start()
    assertThat(run.readyItems).isEqualTo(4)
    // Dopo la verifica la riga risulta fuori dalla recinzione (spostata dall'utente): esce prima del dialogo.
    bench.catalog.change(moved.id) { media ->
      media.copy(locations = media.locations.map { loc ->
        val phone = loc.location as? dev.pampa.mycloudgallery.core.model.Location.Phone ?: return@map loc
        loc.copy(location = phone.copy(relativePath = "DCIM/Camera/"))
      })
    }
    val done = bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactlyElementsIn(inside.map { it.phoneId })
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.OUTSIDE_TEST_FENCE, 1)
    assertThat(bench.phone.ref(outside.phoneId!!)).isNotNull()
  }

  @Test
  fun unGiroAllaVolta_unGiroNuovoChiudeQuelloDiPrima_eLaRispostaDelSuoDialogoSiRegistraLostesso() = runTest {
    repeat(3) { bench.safe() }
    val first = bench.start()
    val batch = bench.space.nextBatch(first)!!
    // Il dialogo del primo giro e' aperto quando l'utente (un doppio tocco, un altro foglio) fa partire un secondo giro.
    val second = bench.start()
    assertThat(bench.refresh(first).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(bench.space.nextBatch(first)).isNull()
    // Le righe del primo lotto sono nel dialogo: il secondo giro non le ha (nessuna riga in due dialoghi).
    assertThat(second.readyItems).isEqualTo(0)
    val recorded = bench.space.recordOutcome(batch, bench.dialog(batch))
    assertThat(recorded.state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(recorded.freedItems).isEqualTo(3)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  @Test
  fun senzaIlDialogoDiSistemaLiberaSpazioNonEsiste() = runTest {
    bench.safe()
    bench.systemDialogs = false
    bench.restart()
    assertThat(bench.start().blocked).isEqualTo(FreeSpaceBlock.NO_SYSTEM_DIALOG)
  }

  @Test
  fun unGiroSconosciuto_eUnPianoVuoto() = runTest {
    // Un piano vuoto e' un giro finito subito, con l'avviso forte.
    val empty = bench.start()
    assertThat(empty.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(bench.space.nextBatch(empty)).isNull()
    // Dopo un azzeramento il diario e' vuoto: un giro che la UI ricorda e' fermo, nessun lotto.
    val other = FreeSpaceBench()
    assertThat(other.space.nextBatch(empty)).isNull()
    assertThat(other.space.resume(empty, 1.hours).blocked).isEqualTo(FreeSpaceBlock.NOT_AVAILABLE)
  }

  @Test
  fun dueCopieDelTelefonoDelloStessoMedia_unaRiletturaSola() = runTest {
    val m = bench.safe()
    // La stessa foto in due cartelle del telefono (E-L3): due righe, una versione, una copia privata da rileggere.
    val phoneId = 9_000L
    bench.phone.add(phoneId, NasContent.of(m.data.getValue(m.current)))
    bench.catalog.change(m.id) { media ->
      media.copy(
        locations = media.locations + dev.pampa.mycloudgallery.core.model.MediaLocation(
          id = bench.newLocationId(),
          versionId = m.current,
          location = dev.pampa.mycloudgallery.core.model.Location.Phone("external_primary", "Pictures/Copie/", m.name, phoneId, bench.phone.version),
          stamp = bench.phone.stamp(phoneId)!!,
          state = LocationState.PRESENT,
          lastSeenAtMillis = 0,
        ),
      )
    }
    val opens = bench.nas.opens.get()
    val run = bench.start()
    assertThat(run.readyItems).isEqualTo(2)
    assertThat(bench.nas.opens.get() - opens).isEqualTo(1)
    bench.freeAll(run)
    assertThat(bench.removed).hasSize(2)
  }
}
