package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import org.junit.Test

/**
 * **Fase I (12/11, BF-2)**: la durata del primo backup in corso, sui numeri del telefono dell'utente: 7.105 media (5.559 gia' sul NAS da rileggere, 1.546 da
 * caricare) a **65 media al minuto costanti**. Prima la stima saliva da 2 h 12 a 7 h 27 mentre il lavoro andava a ritmo costante. Adesso: nessuna durata
 * finche' non ci sono 15 minuti di lavoro e 100 media fatti; poi il ritmo misurato per media, arrotondato (5 minuti; mezz'ore sopra le 2 ore), che scende.
 */
class RunProgressFirstBackupTest {

  private val minute = 60_000L

  private fun batch(done: Int) = BackupStatus.Running(
    kind = BackupKind.PHOTOS, done = done, total = 980, bytesDone = 0L, bytesTotal = 0L, etaMillis = null, trigger = BackupTrigger.FIRST_BACKUP,
  )

  /** Il primo backup: [steps] osservazioni a [stepMs] l'una, con [perStep] media fatti ciascuna (13 ogni 12 s = 65 al minuto, il telefono); le durate viste. */
  private fun run(progress: RunProgress, total: Int, perStep: Int, stepMs: Long, steps: Int): List<Long?> {
    var queued = total
    val seen = mutableListOf<Long?>()
    progress.reshape(batch(done = 0), queued = queued, nowMs = 0)
    for (i in 1..steps) {
      queued = (queued - perStep).coerceAtLeast(0)
      seen += progress.reshape(batch(done = 0), queued = queued, nowMs = i * stepMs).etaMillis
    }
    return seen
  }

  @Test
  fun nessunaDurataPrimaDiQuindiciMinuti_PoiQuellaDelRitmoMisurato() {
    val progress = RunProgress()
    // 15 minuti a 65 al minuto, un'osservazione ogni 12 s: 75 osservazioni.
    val etas = run(progress, total = 7_105, perStep = 13, stepMs = 12_000L, steps = 75)
    assertWithMessage("prima dei 15 minuti").that(etas.dropLast(1).all { it == null }).isTrue()
    // A 15 minuti: 975 fatti, 6.130 in coda, 65 al minuto: 94,3 minuti -> 95 minuti.
    assertThat(etas.last()).isEqualTo(95 * minute)
  }

  @Test
  fun aRitmoCostanteLaDurataScendeEMaiSale() {
    val progress = RunProgress()
    // Un'ora di primo backup: 300 osservazioni da 12 s.
    val etas = run(progress, total = 7_105, perStep = 13, stepMs = 12_000L, steps = 300).filterNotNull()
    assertThat(etas).isNotEmpty()
    for (i in 1 until etas.size) assertWithMessage("osservazione $i").that(etas[i]).isAtMost(etas[i - 1])
    // A 60 minuti: 3.900 fatti, 3.205 in coda: 49,3 minuti -> 50 minuti.
    assertThat(etas.last()).isEqualTo(50 * minute)
    // Mai sopra le 2 ore per un lavoro che a quel ritmo dura meno di un'ora e tre quarti in tutto (prima: 4 h 58, poi 7 h 27).
    assertThat(etas.max()).isAtMost(2 * 60 * minute)
  }

  @Test
  fun pochiMediaInQuindiciMinutiNonBastanoPerUnaDurata() {
    val progress = RunProgress()
    // 20 minuti di lavoro (un'osservazione ogni 15 s, un media ciascuna): 80 fatti, sotto i 100.
    val etas = run(progress, total = 7_105, perStep = 1, stepMs = 15_000L, steps = 80)
    assertThat(etas.all { it == null }).isTrue()
  }

  @Test
  fun unaStimaUnPoPiuAltaNonSostituisceQuellaMostrata() {
    val progress = RunProgress()
    run(progress, total = 7_105, perStep = 13, stepMs = 12_000L, steps = 75)
    val shown = progress.firstBackupEta(queued = 6_130)
    // Un po' piu' lenta (stessi fatti, piu' coda: arrivano foto nuove): meno di un quinto in piu', resta quella mostrata.
    assertThat(progress.firstBackupEta(queued = 6_400)).isEqualTo(shown)
    // Molto piu' lenta: la stima nuova.
    assertThat(progress.firstBackupEta(queued = 9_000)).isGreaterThan(shown!!)
  }

  @Test
  fun leDurateSonoArrotondateComeLeDirebbeUnaPersona() {
    assertThat(RunProgress.roundEta(94 * minute + 18_000)).isEqualTo(95 * minute)
    assertThat(RunProgress.roundEta(3 * minute)).isEqualTo(5 * minute)
    // Sopra le 2 ore, alla mezz'ora: 2 h 13 -> 2 h; 7 h 27 -> 7 h 30.
    assertThat(RunProgress.roundEta(133 * minute)).isEqualTo(120 * minute)
    assertThat(RunProgress.roundEta(447 * minute)).isEqualTo(450 * minute)
  }
}
