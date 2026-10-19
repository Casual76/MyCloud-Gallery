package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.ConfirmablePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceWarning
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationIssuer
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.testing.uuidOf
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5B (P0-B, D21, R6 della 4B): la conferma `PhoneConfirmation` e il foglio a tocchi, sorelle di `UserConfirmation` e `ConfirmationSheet`
 * della 4B. Vale per un piano (l'impronta), per 10 minuti di orologio monotono, e il definitivo vuole due gesti distinti a 600 ms l'uno
 * dall'altro. Il test di architettura (`Phase5BArchitectureTest`) garantisce che la costruisca solo la UI.
 */
class FiveBConfirmationTest {

  private fun item(n: Long, size: Long = 1_000, safe: NasStamp? = NasStamp(size, 5_000, n)) = FreeSpaceItem(
    mediaId = MediaId(uuidOf(n)),
    versionId = VersionId(uuidOf(n + 1_000)),
    phone = PhoneRef("external_primary", n + 1, "v1"),
    mime = "image/jpeg",
    size = size,
    safeCopy = safe,
    isVideo = false,
    localDay = 20_240_101,
  )

  private fun plan(permanent: Boolean = false, items: List<FreeSpaceItem> = listOf(item(1), item(2))) =
    FreeSpacePlan(FreeSpaceFilter(), permanent, items, emptyMap())

  @Test
  fun laConfermaVaConUnPianoSoloPerDieciMinutiMonotoni() {
    val p = plan()
    val c = PhoneConfirmationIssuer.issue(p, confirmedSteps = 1, nowMonotonicMs = 100_000)
    assertThat(PhoneConfirmationRules.check(p, c, 100_000)).isEqualTo(PhoneConfirmationCheck.Valid)
    assertThat(PhoneConfirmationRules.check(p, c, 100_000 + PhoneConfirmationRules.MAX_AGE_MS)).isEqualTo(PhoneConfirmationCheck.Valid)
    assertThat(PhoneConfirmationRules.check(p, c, 100_001 + PhoneConfirmationRules.MAX_AGE_MS)).isEqualTo(PhoneConfirmationCheck.Expired)
    // Un istante monotono nel futuro non e' di questo avvio del telefono: nessuna conferma sopravvive a un riavvio.
    assertThat(PhoneConfirmationRules.check(p, c, 99_999)).isEqualTo(PhoneConfirmationCheck.FromTheFuture)
    // La conferma non apre un piano ricalcolato: un elemento in piu' vuole un altro tocco.
    assertThat(PhoneConfirmationRules.check(plan(items = listOf(item(1), item(2), item(3))), c, 100_000)).isEqualTo(PhoneConfirmationCheck.WrongPlan)
    assertThat(c.toString()).doesNotContain(p.hash.hex)
  }

  @Test
  fun unPianoDefinitivoVuoleDueTocchiELaConfermaConUnoSoloNonSiCostruisce() {
    val definitive = plan(permanent = true)
    assertThat(definitive.requiredConfirmations).isEqualTo(2)
    assertThat(definitive.warnings).containsExactly(FreeSpaceWarning.NO_EXTERNAL_BACKUP, FreeSpaceWarning.PERMANENT)
    assertThrows(IllegalArgumentException::class.java) { PhoneConfirmationIssuer.issue(definitive, confirmedSteps = 1, nowMonotonicMs = 0) }
    assertThat(PhoneConfirmationIssuer.issue(definitive, confirmedSteps = 2, nowMonotonicMs = 0).confirmedSteps).isEqualTo(2)
    // Cestino di Android o definitivo non sono lo stesso piano: l'impronta cambia, la conferma dell'uno non vale per l'altro.
    val trash = plan(permanent = false)
    assertThat(trash.hash).isNotEqualTo(definitive.hash)
    assertThat(trash.requiredConfirmations).isEqualTo(1)
    assertThat(trash.warnings).containsExactly(FreeSpaceWarning.NO_EXTERNAL_BACKUP)
    val c = PhoneConfirmationIssuer.issue(trash, 1, 0)
    assertThat(PhoneConfirmationRules.check(definitive, c, 0)).isEqualTo(PhoneConfirmationCheck.WrongPlan)
  }

  @Test
  fun ilFoglioAUnToccoDaLaConfermaAlPrimoToccoEUnPianoCambiatoLaRifiuta() {
    val p = plan()
    val sheet = PhoneConfirmationSheet(p, shownAtMonotonicMs = 1_000)
    assertThat(sheet.tapsLeft).isEqualTo(1)
    val c = sheet.tap(1_050)!!
    assertThat(PhoneConfirmationRules.check(p, c, 1_050)).isEqualTo(PhoneConfirmationCheck.Valid)
    assertThat(sheet.tapsLeft).isEqualTo(0)
  }

  @Test
  fun ilFoglioADueToccoNonContaUnDoppioToccoNeIlPrimoTroppoVicinoAllaComparsa() {
    val sheet = PhoneConfirmationSheet(plan(permanent = true), shownAtMonotonicMs = 10_000)
    // Il primo tocco a 300 ms dalla comparsa del foglio e' il secondo colpo di un doppio tocco sulla voce del menu: non conta.
    assertThat(sheet.tap(10_300)).isNull()
    assertThat(sheet.tapsDone).isEqualTo(0)
    // Dopo aver letto, il primo tocco conta; un secondo colpo a 300 ms no; il secondo gesto, a piu' di 600 ms, si'.
    assertThat(sheet.tap(11_000)).isNull()
    assertThat(sheet.tapsDone).isEqualTo(1)
    assertThat(sheet.tap(11_300)).isNull()
    assertThat(sheet.tapsDone).isEqualTo(1)
    val c = sheet.tap(11_700)
    assertThat(c).isNotNull()
    assertThat(c!!.confirmedSteps).isEqualTo(2)
    // L'utente rinuncia: il foglio riparte da capo.
    sheet.reset()
    assertThat(sheet.tapsLeft).isEqualTo(2)
  }

  @Test
  fun lImprontaDipendeDaOgniElementoDalTimbroEDalFiltro() {
    val base = plan()
    assertThat(plan().hash).isEqualTo(base.hash)
    // Un timbro verificato diverso (la copia e' stata toccata), un'altra dimensione, un altro ordine: un altro piano.
    assertThat(plan(items = listOf(item(1), item(2, safe = NasStamp(1_000, 9_999, 2)))).hash).isNotEqualTo(base.hash)
    assertThat(plan(items = listOf(item(1), item(2, size = 2_000))).hash).isNotEqualTo(base.hash)
    assertThat(plan(items = listOf(item(2), item(1))).hash).isNotEqualTo(base.hash)
    assertThat(FreeSpacePlan(FreeSpaceFilter(excludeRecentDays = 0), false, base.items, emptyMap()).hash).isNotEqualTo(base.hash)
    // Piani di tipo diverso con gli stessi pezzi non hanno mai la stessa impronta.
    assertThat(PhonePlanHash.builder("a").string("x").build()).isNotEqualTo(PhonePlanHash.builder("b").string("x").build())
    assertThat(PhonePlanHash.builder("a").string(null).build()).isNotEqualTo(PhonePlanHash.builder("a").string("").build())
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonVale() {
    val a = plan()
    val b = plan(items = listOf(item(7)))
    val c = PhoneConfirmationIssuer.issue(a as ConfirmablePlan, 1, 0)
    assertThat(PhoneConfirmationRules.check(b, c, 0)).isEqualTo(PhoneConfirmationCheck.WrongPlan)
  }
}
