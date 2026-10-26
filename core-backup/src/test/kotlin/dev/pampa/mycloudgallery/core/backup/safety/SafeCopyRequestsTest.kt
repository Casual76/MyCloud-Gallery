package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 7 P4, D28 ("al sicuro" garantito) con Q4 (b) e la conferma del 22/10 (correzioni dal campo A, D2): la richiesta del guardiano dopo una
 * condivisione ([GuardianSafeCopyRequests]) si registra prima del ritorno e sveglia il lavoro; il giro chiesto e' quello di sempre
 * (`GuardScope.All`): la copia privata nasce **solo** per i media propri che il telefono **ha avuto** e non ha piu'; un media mai stato sul
 * telefono (messo sul NAS dal PC) resta protetto dal cestino del NAS (L44, L62). Le richieste si tolgono solo a giro finito.
 */
class SafeCopyRequestsTest {

  private val bench = SafetyBench()
  private val kit = bench.kit

  @Test
  fun ilGuardianoSubitoSoloPerIMediaCheIlTelefonoHaAvuto() = runTest {
    val freed = bench.commonOnly("IMG_LIBERATA.jpg", SafetyBench.bytes(30_000, 1))
    val fromPc = bench.commonOnly("IMG_PC.jpg", SafetyBench.bytes(30_000, 2), wasOnPhone = false)
    val log = MemorySafeCopyRequestLog()
    var wakes = 0
    val requests = GuardianSafeCopyRequests(log, bench.guardian()) { wakes++ }
    requests.requestNow(listOf(freed.mediaId, fromPc.mediaId))
    assertThat(wakes).isEqualTo(1)
    assertThat(requests.hasPending()).isTrue()
    // La richiesta e' registrata prima del ritorno: un processo nuovo (stesso registro) la ritrova.
    val afterDeath = GuardianSafeCopyRequests(log, bench.guardian()) { wakes++ }
    val report = afterDeath.runRequested(1.hours)!!
    assertThat(report.created).isEqualTo(1)
    assertThat(kit.catalog.isSafe(freed.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(fromPc.mediaId)).isFalse()
    assertThat(afterDeath.hasPending()).isFalse()
    assertThat(afterDeath.runRequested(1.hours)).isNull()
    // Niente richiesto, niente svegliato.
    requests.requestNow(emptyList())
    assertThat(wakes).isEqualTo(1)
  }

  /** Un giro fermato (rete, NAS pieno, tempo finito) lascia le richieste per il giro dopo. */
  @Test
  fun unGiroFermatoLasciaLeRichieste() = runTest {
    val log = MemorySafeCopyRequestLog()
    var stop = RunStop.NAS
    val guardian = object : PrivateCopyGuardian {
      val scopes = mutableListOf<GuardScope>()

      override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
        scopes += scope
        return GuardReport(0, 0, 0, 0, 0L, stop)
      }
    }
    val requests = GuardianSafeCopyRequests(log, guardian) {}
    val media = bench.commonOnly("IMG_X.jpg", SafetyBench.bytes(1_000, 3)).mediaId
    requests.requestNow(listOf(media))
    requests.runRequested(1.hours)
    assertThat(requests.hasPending()).isTrue()
    stop = RunStop.COMPLETE
    requests.runRequested(1.hours)
    assertThat(requests.hasPending()).isFalse()
    // Sempre l'ambito di sempre (Q4 (b)): mai `GuardScope.Media`, che copierebbe anche i media mai stati sul telefono.
    assertThat(guardian.scopes.toSet()).containsExactly(GuardScope.All)
  }
}
