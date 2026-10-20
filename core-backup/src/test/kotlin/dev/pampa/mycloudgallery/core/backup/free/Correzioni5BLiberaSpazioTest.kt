package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Le correzioni di "Libera spazio" della verifica funzionale 5B (`docs/review/funzionale-5b.md`: LS-2, LS-8, LS-11, LS-12, LS-13) sul motore
 * vero e sul banco di P9 (NAS finto, telefono finto, catalogo e diari). Ogni test falliva prima della correzione (o non compilava, per i metodi
 * nuovi `active` e `abandon`). La regola d'oro non cambia: ogni lotto che esce e' controllato contro la verita' del NAS finto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Correzioni5BLiberaSpazioTest {

  private val bench = FreeSpaceBench()

  // --- LS-13: la conferma vale per il piano che l'utente ha visto ------------------------------------------------------------------

  /**
   * LS-13: durante il primo backup un media diventa al sicuro fra l'apertura del foglio e il tocco. Prima la conferma era rifiutata ("Qualcosa
   * e' cambiato") a ogni tentativo; adesso il giro parte con i media confermati, e quello nuovo **non entra** (la lista non cresce mai).
   */
  @Test
  fun ls13_unMediaDiventatoAlSicuroDopoIlFoglioNonEntra_eIlGiroParte() = runTest {
    val a = bench.safe()
    val b = bench.safe()
    val (plan, confirmation) = bench.confirmed()
    assertThat(plan.itemCount).isEqualTo(2)
    val late = bench.safe()
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(2)
    // Il giro porta l'impronta del piano confermato.
    assertThat(run.planHash).isEqualTo(plan.hash)
    bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(a.phoneId, b.phoneId)
    assertThat(bench.phone.ref(late.phoneId!!)).isNotNull()
  }

  /** LS-13: la copia privata di un media confermato ha un timbro nuovo (il PC l'ha toccata): quel media resta sul telefono, gli altri vanno. */
  @Test
  fun ls13_unaCopiaCambiataDopoIlFoglioRestaSulTelefono_gliAltriSiLiberano() = runTest {
    val changed = bench.safe()
    val a = bench.safe()
    val b = bench.safe()
    val (plan, confirmation) = bench.confirmed()
    bench.catalog.change(changed.id) { media ->
      media.copy(locations = media.locations.map { if (it.isNas) it.copy(stamp = it.stamp.copy(modifiedAtMillis = it.stamp.modifiedAtMillis + 1)) else it })
    }
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(2)
    bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(a.phoneId, b.phoneId)
    assertThat(bench.phone.ref(changed.phoneId!!)).isNotNull()
  }

  /** LS-13: se nessun media confermato e' ancora uguale, il giro finisce subito e non chiede niente al telefono. */
  @Test
  fun ls13_seNessunMediaConfermatoEUguale_nienteVaAlTelefono() = runTest {
    val m = bench.safe()
    val (plan, confirmation) = bench.confirmed()
    bench.catalog.setProblem(m.id, dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode.VERIFY_FAILED)
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.removed).isEmpty()
  }

  // --- LS-2: il giro si riprende ----------------------------------------------------------------------------------------------------

  /** LS-2: dopo la morte del processo, nello stesso avvio e entro le 6 ore, il giro a meta' si ritrova e si finisce. */
  @Test
  fun ls2_ilGiroApertoSiRitrovaDopoLaMorteDelProcesso_eSiFinisce() = runTest {
    repeat(3) { bench.safe() }
    val half = bench.start(budget = Duration.ZERO)
    assertThat(half.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    bench.restart()
    val found = checkNotNull(bench.space.active())
    assertThat(found.id).isEqualTo(half.id)
    assertThat(found.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    val ready = bench.space.resume(found, 1.hours)
    assertThat(ready.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(bench.freeAll(ready).freedItems).isEqualTo(3)
    assertThat(bench.space.active()).isNull()
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  /** LS-2: dopo un riavvio del telefono, o oltre le 6 ore monotone, il giro aperto si chiude scaduto (una volta) e niente esce. */
  @Test
  fun ls2_dopoUnRiavvioOOltreLeSeiOreIlGiroApertoScade() = runTest {
    repeat(2) { bench.safe() }
    val half = bench.start(budget = Duration.ZERO)
    bench.bootCount = bench.bootCount!! + 1
    bench.restart()
    val found = checkNotNull(bench.space.active())
    assertThat(found.id).isEqualTo(half.id)
    assertThat(found.state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(bench.space.active()).isNull()

    bench.start(budget = Duration.ZERO)
    bench.clock.monotonic += FreeSpaceRules.VERIFICATION_VALID_MS + 1
    assertThat(checkNotNull(bench.space.active()).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(bench.removed).isEmpty()
  }

  /** LS-2: "Annulla" chiude il giro; non lo riprende piu' nessuno (ne' la pagina ne' il lavoro). */
  @Test
  fun ls2_annullaChiudeIlGiroENonSiRiprende() = runTest {
    repeat(2) { bench.safe() }
    val half = bench.start(budget = Duration.ZERO)
    assertThat(checkNotNull(bench.space.abandon(half.id)).state).isEqualTo(FreeSpaceRunState.CANCELLED)
    assertThat(bench.space.active()).isNull()
    assertThat(bench.space.resume(half, 1.hours).state).isEqualTo(FreeSpaceRunState.CANCELLED)
    assertThat(bench.space.nextBatch(half)).isNull()
    assertThat(bench.removed).isEmpty()
  }

  // --- LS-8: il guardiano fuori dal lucchetto ------------------------------------------------------------------------------------

  /**
   * LS-8: il guardiano aspetta il turno del backup ("Fai ora" in corso). Prima teneva il lucchetto di "Libera spazio" per tutta l'attesa (fino a
   * 15 minuti): `settle()` dopo la lettura del telefono restava fermo. Adesso risponde subito, e il giro finisce quando il turno si libera.
   */
  @Test
  fun ls8_ilGuardianoCheAspettaIlTurnoNonTieneFermoSettle() = runTest {
    bench.safe(privateCopy = false, commonCopy = true)
    val turn = CompletableDeferred<Unit>()
    val waiting = object : PrivateCopyGuardian {
      override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
        turn.await()
        return bench.guardian.ensure(scope, budget)
      }
    }
    val space = bench.engine(guardian = waiting)
    val (plan, confirmation) = bench.confirmed()
    assertThat(plan.needsGuardian).isEqualTo(1)
    val verifying = async { space.verify(plan, confirmation, 1.hours) }
    runCurrent()
    assertThat(verifying.isActive).isTrue()
    assertThat(withTimeout(1_000) { space.settle() }).isEqualTo(0)
    turn.complete(Unit)
    assertThat(verifying.await().state).isEqualTo(FreeSpaceRunState.READY)
  }

  // --- LS-11: senza l'ora del NAS ----------------------------------------------------------------------------------------------------

  /** LS-11: appena aperta l'app nessuna sessione ha ancora l'ora del NAS: la verifica lo raggiunge prima di dire "il NAS non risponde". */
  @Test
  fun ls11_senzaLOraDelNas_laVerificaLoRaggiungePrima() = runTest {
    repeat(2) { bench.safe() }
    val (plan, confirmation) = bench.confirmed()
    bench.clock.nasNow = null
    var contacts = 0
    bench.reachNas = {
      contacts++
      bench.clock.nasNow = FakeBackupClock.DEFAULT_NAS_NOW
    }
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(contacts).isEqualTo(1)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
  }

  /** LS-11: se il NAS non si raggiunge davvero, il giro lo dice (E-B9) e niente si libera. */
  @Test
  fun ls11_seIlNasNonSiRaggiunge_laVerificaLoDice() = runTest {
    bench.safe()
    val (plan, confirmation) = bench.confirmed()
    bench.clock.nasNow = null
    bench.reachNas = { throw IOException("NAS spento") }
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.blocked).isEqualTo(FreeSpaceBlock.NAS_UNREACHABLE)
    assertThat(bench.space.nextBatch(run)).isNull()
  }

  // --- LS-12: le righe "in dubbio" e "nel dialogo" -----------------------------------------------------------------------------------

  /**
   * LS-12: l'app muore con il dialogo di sistema aperto (l'utente conferma solo [a]) e poi il MediaStore si ricostruisce: le righe non valgono
   * piu' (`Stale`). Prima restavano in dubbio per sempre; adesso le risolve la lettura del telefono, con la stessa ricetta del backup.
   */
  @Test
  fun ls12_unaRigaRinumerataDopoIlDialogoSiRisolveConLaLetturaDelTelefono() = runTest {
    val a = bench.safe()
    val b = bench.safe()
    val c = bench.safe()
    val run = bench.start()
    val batch = checkNotNull(bench.space.nextBatch(run))
    bench.dialog(batch, only = setOf(a.phoneRef!!))
    bench.phone.rebuild("v2", renumber = mapOf(b.phoneId!! to 900L, c.phoneId!! to 901L))
    bench.catalog.phoneRows[a.current] = PhoneRowNow.Absent
    bench.catalog.phoneRows[b.current] = PhoneRowNow.At(PhoneRef(FakePhoneBytes.VOLUME, 900L, "v2"))
    // Per [c] la lettura del telefono non ha ancora finito: resta in dubbio, mai liberato senza prova.
    bench.catalog.phoneRows[c.current] = PhoneRowNow.Rereading
    bench.restart()
    bench.space.settle()
    fun state(m: FreeSpaceBench.Made) = bench.diary.everything().map { it.second }.single { it.mediaId == m.id }.state
    assertThat(state(a)).isEqualTo(FreeItemState.FREED)
    assertThat(state(b)).isEqualTo(FreeItemState.KEPT)
    assertThat(state(c)).isEqualTo(FreeItemState.IN_DOUBT)
    assertThat(bench.catalog.freed).containsExactly(a.phoneLocation)
    bench.catalog.phoneRows[c.current] = PhoneRowNow.At(PhoneRef(FakePhoneBytes.VOLUME, 901L, "v2"))
    bench.space.settle()
    assertThat(state(c)).isEqualTo(FreeItemState.KEPT)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  /**
   * LS-12: l'utente conferma nel dialogo, Android toglie le righe, ma la risposta non arriva alla pagina (il suo lavoro si e' chiuso) e il giro
   * poi scade. Prima le righe restavano `IN_DIALOG` (fuori da ogni piano) fino alla morte del processo; adesso `settle()` le vede tolte e le
   * da' per liberate. Una riga ancora sul telefono invece resta nel dialogo (puo' ancora rispondere: la risposta si registra anche a giro
   * finito), con il suo motivo nel riepilogo.
   */
  @Test
  fun ls12_unLottoDiQuestoProcessoInUnGiroFinito_leRigheTolteSiRisolvono() = runTest {
    val a = bench.safe()
    val b = bench.safe()
    val run = bench.start()
    val batch = checkNotNull(bench.space.nextBatch(run))
    bench.dialog(batch, only = setOf(a.phoneRef!!))
    bench.clock.monotonic += FreeSpaceRules.VERIFICATION_VALID_MS + 1
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.refresh(run).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    bench.space.settle()
    fun state(m: FreeSpaceBench.Made) = bench.diary.everything().map { it.second }.single { it.mediaId == m.id }.state
    assertThat(state(a)).isEqualTo(FreeItemState.FREED)
    assertThat(state(b)).isEqualTo(FreeItemState.IN_DIALOG)
    assertThat(bench.space.plan(FreeSpaceFilter()).excluded).containsExactly(FreeSpaceExclusion.AWAITING_PHONE, 1)
    // La risposta arriva tardi (il dialogo era ancora aperto): si registra, nessuna traccia persa.
    val after = bench.space.recordOutcome(batch, bench.dialog(batch))
    assertThat(after.freedItems).isEqualTo(2)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  /** LS-12: nel riepilogo le righe gia' consegnate ad Android hanno il loro motivo (prima "in caricamento o con un problema"). */
  @Test
  fun ls12_leRigheGiaConsegnateHannoIlLoroMotivo() = runTest {
    val a = bench.safe()
    bench.safe()
    val run = bench.start()
    checkNotNull(bench.space.nextBatch(run))
    assertThat(bench.space.plan(FreeSpaceFilter()).excluded).containsExactly(FreeSpaceExclusion.AWAITING_PHONE, 2)
    assertThat(bench.space.assessPhoneOnly(setOf(a.id)).advice.getValue(a.id)).isEqualTo(PhoneOnlyAdvice.WAIT)
  }
}
