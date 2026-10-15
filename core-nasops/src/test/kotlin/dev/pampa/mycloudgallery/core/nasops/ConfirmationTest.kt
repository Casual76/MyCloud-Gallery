package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationCheck
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationGate
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La conferma dell'utente (D24): vale per UN piano, per poco tempo, con i tocchi richiesti. `NasOperations.start` e il diario la
 * pretendono; chi la costruisce e' verificato da `Phase4ArchitectureTest`.
 */
class ConfirmationTest {

  private val now = 50_000L

  @Test
  fun laConfermaLegaIlPianoEValeDiecIMinuti() {
    val plan = PlanFixtures.trash(3)
    val confirmation = ConfirmationIssuer.issue(plan, 1, now)
    assertThat(confirmation.planHash).isEqualTo(plan.hash)
    assertThat(ConfirmationRules.check(plan, confirmation, now)).isEqualTo(ConfirmationCheck.Valid)
    assertThat(ConfirmationRules.check(plan, confirmation, now + ConfirmationRules.MAX_AGE_MS)).isEqualTo(ConfirmationCheck.Valid)
    assertThat(ConfirmationRules.check(plan, confirmation, now + ConfirmationRules.MAX_AGE_MS + 1)).isEqualTo(ConfirmationCheck.Expired)
  }

  @Test
  fun unaConfermaNonVaPerUnAltroPiano() {
    val confirmation = ConfirmationIssuer.issue(PlanFixtures.trash(3), 1, now)
    // Il piano e' cambiato dopo il foglio (un elemento in piu'): la conferma non vale piu'.
    assertThat(ConfirmationRules.check(PlanFixtures.trash(4), confirmation, now)).isEqualTo(ConfirmationCheck.WrongPlan)
    assertThat(ConfirmationRules.check(PlanFixtures.rename("a.jpg", "b.jpg"), confirmation, now)).isEqualTo(ConfirmationCheck.WrongPlan)
  }

  @Test
  fun unaConfermaDalFuturoNonEDiQuestoAvvio() {
    val plan = PlanFixtures.trash()
    val confirmation = ConfirmationIssuer.issue(plan, 1, now + 1)
    assertThat(ConfirmationRules.check(plan, confirmation, now)).isEqualTo(ConfirmationCheck.FromTheFuture)
  }

  @Test
  fun eliminaSubitoVuoleDueConferme() {
    val plan = PlanFixtures.purge()
    assertThat(plan.kind.permanent).isTrue()
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    assertThrows(IllegalArgumentException::class.java) { ConfirmationIssuer.issue(plan, 1, now) }
    assertThat(ConfirmationIssuer.issue(plan, 2, now).confirmedSteps).isEqualTo(2)
  }

  @Test
  fun ilFoglioDiConfermaDaLUnicaConfermaSoloAllUltimoTocco() {
    val simple = ConfirmationSheet(PlanFixtures.trash())
    assertThat(simple.tapsLeft).isEqualTo(1)
    assertThat(simple.tap(now)).isNotNull()

    val permanent = ConfirmationSheet(PlanFixtures.purge())
    assertThat(permanent.tapsLeft).isEqualTo(2)
    assertThat(permanent.tap(now)).isNull()
    assertThat(permanent.tapsLeft).isEqualTo(1)
    permanent.reset()
    assertThat(permanent.tapsLeft).isEqualTo(2)
    assertThat(permanent.tap(now)).isNull()
    // R6: il secondo tocco e' un secondo gesto, non il secondo colpo di un doppio tocco.
    val later = now + ConfirmationRules.MIN_TAP_GAP_MS
    val confirmation = permanent.tap(later)
    assertThat(confirmation).isNotNull()
    assertThat(ConfirmationRules.check(permanent.plan, confirmation!!, later)).isEqualTo(ConfirmationCheck.Valid)
  }

  @Test
  fun r6_unDoppioToccoNonFaLaDoppiaConfermaENemmenoUnToccoAppenaComparsoIlFoglio() {
    // Il secondo colpo di un doppio tocco (40 ms) non conta: resta da fare il secondo gesto.
    val sheet = ConfirmationSheet(PlanFixtures.purge())
    assertThat(sheet.tap(now)).isNull()
    assertThat(sheet.tap(now + 40)).isNull()
    assertThat(sheet.tapsLeft).isEqualTo(1)
    assertThat(sheet.tap(now + ConfirmationRules.MIN_TAP_GAP_MS - 1)).isNull()
    assertThat(sheet.tap(now + ConfirmationRules.MIN_TAP_GAP_MS)!!.confirmedSteps).isEqualTo(2)
    // Un tocco arrivato con il foglio appena comparso (il secondo colpo sulla voce del menu) non conta nemmeno come primo.
    val shown = ConfirmationSheet(PlanFixtures.purge(), shownAtMonotonicMs = now)
    assertThat(shown.tap(now + 100)).isNull()
    assertThat(shown.tapsLeft).isEqualTo(2)
    // Un foglio a un tocco solo (cestino del NAS, 30 giorni) non cambia.
    assertThat(ConfirmationSheet(PlanFixtures.trash(), shownAtMonotonicMs = now).tap(now)).isNotNull()
  }

  @Test
  fun ilCancelloFaPassareSoloUnaConfermaValida() {
    val plan = PlanFixtures.trash(2)
    val good = ConfirmationIssuer.issue(plan, 1, now)
    val accepted = ConfirmationGate.accept(plan, good, now)
    assertThat(accepted).isInstanceOf(AcceptOutcome.Accepted::class.java)
    assertThat((accepted as AcceptOutcome.Accepted).accepted.plan).isSameInstanceAs(plan)

    assertThat(ConfirmationGate.accept(plan, good, now + ConfirmationRules.MAX_AGE_MS + 1)).isEqualTo(AcceptOutcome.Rejected(ConfirmationCheck.Expired))
    assertThat(ConfirmationGate.accept(PlanFixtures.trash(3), good, now)).isEqualTo(AcceptOutcome.Rejected(ConfirmationCheck.WrongPlan))
  }

  @Test
  fun laFormaDelleApiChiedeLaConferma() {
    // `start` prende una UserConfirmation: non c'e' modo di avviare un'operazione senza.
    val start = NasOperations::class.java.methods.single { it.name.startsWith("start") }
    assertThat(start.parameterTypes.toList()).contains(UserConfirmation::class.java)
    // Il diario crea un'operazione solo da un piano accettato.
    val create = OpsStore::class.java.methods.single { it.name.startsWith("create") }
    assertThat(create.parameterTypes.map { it.simpleName }).contains("AcceptedPlan")
    // Una conferma non rivela dati: niente percorsi nel testo.
    assertThat(ConfirmationIssuer.issue(PlanFixtures.trash(), 1, now).toString()).doesNotContain("Foto")
  }

  @Test
  fun ilPianoNonRivelaIPercorsiNelTesto() {
    val plan: OpPlan = PlanFixtures.trash(2)
    assertThat(plan.toString()).doesNotContain("Foto")
    assertThat(plan.items.first().toString()).doesNotContain("Foto")
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
  }
}
