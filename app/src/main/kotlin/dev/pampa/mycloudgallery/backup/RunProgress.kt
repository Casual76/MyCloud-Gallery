package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.rules.estimate.BackupCosts
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.core.rules.estimate.Workload
import java.util.concurrent.atomic.AtomicInteger

/**
 * **BK-5** (verifica funzionale): l'avanzamento di un lavoro del backup attraverso i lotti e le tappe. Il motore pubblica lo stato `Running` per
 * **lotto** da 60 secondi (`UploadStages`: un giro nuovo per ogni lotto, con i suoi contatori) e non conosce il tempo che manca: il primo
 * backup che era "1.240 di 6.658 · circa 1 h 20 min" diventava "37 di 980" dopo un minuto, e la barra della notifica tornava indietro.
 *
 * Qui il conto sta **fuori dal motore**, sul catalogo: i media ancora da mettere al sicuro (`queued`, gli stessi della pagina) sono l'unica
 * misura che non riparte con un lotto. Ogni volta che scendono, quei media sono "fatti"; ogni volta che salgono (foto nuove arrivate mentre il
 * backup lavora) il totale cresce. Quindi sempre `totale = fatti + in coda`, e i fatti non scendono mai. Il tempo che manca viene dal ritmo
 * **osservato mentre il backup lavorava** (non conta le notti ferme fra due tappe): niente prima di due media fatti.
 *
 * Una campagna finisce quando non c'e' piu' niente in coda; se il backup si ferma (pausa, problema, regole) con altro da fare resta, e
 * continua alla ripresa. Si ricorda su file ([snapshot], [restore]): la morte del processo fra due tappe non la azzera. Solo numeri.
 *
 * **Il punto di partenza sta sul catalogo, non sul motore.** Il catalogo mette un media "al sicuro" prima che il motore lo conti fra i fatti:
 * dopo la copia verificata il motore pubblica ancora le immagini all'indice e chiude il suo diario, e solo allora `done` sale. Chi guarda
 * `done` del motore e `queued` del catalogo nello stesso istante vede quindi i media in volo come "ne' fatti ne' in coda", e il primo stato
 * di una campagna aveva un fatto in meno: totale 23 invece di 24, per tutta la campagna.
 * Per questo il giro che parte dice quanti ne ha in coda il catalogo ([roundStarting]), e i fatti gia' compiuti al primo sguardo sono la
 * differenza con la coda di adesso: stessa fonte, stesso istante, nessun media in volo perso.
 *
 * **D-11** (prove sul campo): con i byte che restano da caricare (`remainingBytes`, dal catalogo e dal diario) il totale dei byte e' quello vero
 * (caricati + da caricare) e il tempo che manca viene dallo stesso modello del riepilogo del primo backup (`BackupCosts`: costo per file, scrittura
 * e rilettura), tarato sul ritmo osservato. Prima si stimavano tutti e due **dal numero** dei media: con 291 foto da 44 byte fatte e 5 video da
 * 2,5 GB in coda la pagina diceva "109 kB di 111 kB · meno di un minuto" per dieci minuti, e il totale scendeva quando cambiava la media.
 */
internal class RunProgress {

  private var done = 0
  private var base = NONE
  private var bytesDone = 0L
  private var roundBytes = 0L
  private var activeMs = 0L
  private var activeDone = 0

  /** D-11: i byte caricati mentre il backup lavorava (con [activeMs] e [activeDone] tarano il modello del tempo che manca). */
  private var activeBytes = 0L
  private var lastRunningAt: Long? = null
  private var changedSinceSnapshot = false

  /**
   * I media in coda sul catalogo quando e' partito l'ultimo giro del motore ([NONE] = nessun giro annunciato). Lo scrive chi avvia il giro
   * (un thread di lavoro), lo legge il ricalcolo dello stato: per questo e' atomico, e chi lo legge lo consuma.
   */
  private val queuedAtRoundStart = AtomicInteger(NONE)

  /**
   * Un giro del motore sta per partire e il catalogo ha [queued] media in coda (lo stesso conto di `reshape`). Se questo giro e' l'inizio di una
   * campagna, il primo stato visto parte da qui: vedi la nota della classe.
   */
  fun roundStarting(queued: Int) {
    queuedAtRoundStart.set(queued)
  }

  /**
   * Il progresso da mostrare per lo stato del motore [running] (di questo lotto), con [queued] media ancora in coda e l'ora monotona [nowMs].
   * [remainingBytes] sono i byte che restano da caricare (null = non si sanno: totale e tempo si stimano dal numero dei media, come prima).
   */
  fun reshape(running: BackupStatus.Running, queued: Int, nowMs: Long, remainingBytes: Long? = null): BackupStatus.Running {
    val previousTick = lastRunningAt
    // Si consuma sempre: serve solo al primo sguardo di una campagna, e non deve restare per un giro che non lo e'.
    val startedWith = queuedAtRoundStart.getAndSet(NONE)
    if (base == NONE) {
      // L'inizio di una campagna (o di quello che se ne vede): i media del lotto gia' fatti contano, quelli di prima non si sanno. Quanti sono lo
      // dice il catalogo (coda di quando il giro e' partito meno coda di adesso); senza l'annuncio del giro, il conto del motore.
      done = if (startedWith != NONE) (startedWith - queued).coerceAtLeast(0) else running.done
      bytesDone = running.bytesDone
      roundBytes = running.bytesDone
      activeMs = 0L
      activeDone = done
      activeBytes = 0L
    } else {
      val delta = base - queued
      if (delta > 0) {
        done += delta
        if (previousTick != null) activeDone += delta
      }
      // I byte del lotto ripartono da zero a ogni lotto: si sommano le differenze, mai il valore del lotto intero.
      val moreBytes = if (running.bytesDone >= roundBytes) running.bytesDone - roundBytes else running.bytesDone
      bytesDone += moreBytes
      if (previousTick != null) activeBytes += moreBytes
      roundBytes = running.bytesDone
    }
    if (previousTick != null) activeMs += (nowMs - previousTick).coerceIn(0L, MAX_STEP_MS)
    lastRunningAt = nowMs
    base = queued
    changedSinceSnapshot = true

    val total = done + queued
    if (remainingBytes != null) {
      // D-11: i byte veri e il tempo dal modello del riepilogo, tarato su cio' che si e' visto.
      val left = remainingBytes.coerceAtLeast(0L)
      return running.copy(done = done, total = total, bytesDone = bytesDone, bytesTotal = bytesDone + left, etaMillis = modelEta(queued, left))
    }
    val eta = if (activeDone >= MIN_SAMPLES && activeMs > 0L && queued > 0) activeMs * queued / activeDone else null
    val bytesTotal = if (done > 0 && bytesDone > 0L) maxOf(bytesDone, bytesDone * total / done) else bytesDone + (running.bytesTotal - running.bytesDone).coerceAtLeast(0L)
    return running.copy(done = done, total = total, bytesDone = bytesDone, bytesTotal = bytesTotal, etaMillis = eta)
  }

  /**
   * D-11: il tempo che manca per [queued] media e [remainingBytes] byte con il modello del riepilogo del primo backup (`BackupCosts`: costo per
   * file, scrittura e rilettura di verifica). Appena si e' visto lavorare abbastanza (due media e [MIN_ACTIVE_MS]) il modello si tara sul ritmo
   * vero: il tempo osservato diviso quello che il modello avrebbe previsto per lo stesso lavoro, fra [MIN_PACE] e [MAX_PACE].
   */
  private fun modelEta(queued: Int, remainingBytes: Long): Long? {
    if (queued <= 0) return null
    val ahead = BackupCosts.seconds(Workload(queued.toLong(), remainingBytes), Workload(0L, 0L), MEASURES)
    val seen = BackupCosts.seconds(Workload(activeDone.toLong(), activeBytes), Workload(0L, 0L), MEASURES)
    val calibrated = activeDone >= MIN_SAMPLES && activeMs >= MIN_ACTIVE_MS && seen > 0.0
    val pace = if (calibrated) (activeMs / 1_000.0 / seen).coerceIn(MIN_PACE, MAX_PACE) else 1.0
    return (ahead * pace * 1_000.0).toLong()
  }

  /**
   * Il backup non sta lavorando adesso. Senza niente in coda la campagna e' finita e si azzera; con altro da fare resta (e il ritmo non
   * conta il tempo fermo: la prossima tappa riparte da [reshape] senza un "tick" precedente). Vero se ha chiuso una campagna.
   */
  fun idle(queued: Int): Boolean {
    if (queued == 0) {
      val closing = base != NONE || done != 0
      if (closing) changedSinceSnapshot = true
      reset()
      queuedAtRoundStart.set(NONE)
      return closing
    }
    lastRunningAt = null
    return false
  }

  /** Il testo da salvare se qualcosa e' cambiato dall'ultima volta (null = niente da scrivere); [EMPTY] = togliere il file. */
  fun snapshot(): String? {
    if (!changedSinceSnapshot) return null
    changedSinceSnapshot = false
    return if (base == NONE && done == 0) EMPTY else listOf(done, base, bytesDone, roundBytes, activeMs, activeDone, activeBytes).joinToString(";")
  }

  /** Ripristina quello che [snapshot] aveva salvato (processo nuovo). Un testo illeggibile vale "niente". */
  fun restore(text: String?) {
    reset()
    val parts = text?.split(';')?.mapNotNull { it.toLongOrNull() } ?: return
    // Sei numeri (prima di D-11) o sette (con i byte caricati mentre si lavorava).
    if ((parts.size != 6 && parts.size != 7) || parts.any { it < 0 && it != NONE.toLong() }) return
    done = parts[0].toInt()
    base = parts[1].toInt()
    bytesDone = parts[2]
    roundBytes = parts[3]
    activeMs = parts[4]
    activeDone = parts[5].toInt()
    activeBytes = parts.getOrNull(6) ?: 0L
  }

  private fun reset() {
    done = 0
    base = NONE
    bytesDone = 0L
    roundBytes = 0L
    activeMs = 0L
    activeDone = 0
    activeBytes = 0L
    lastRunningAt = null
  }

  companion object {
    private const val NONE = -1

    /** Nessun file: la campagna e' finita. */
    const val EMPTY: String = ""

    /** Due media fatti prima di dire un tempo: con uno solo sarebbe una speranza. */
    private const val MIN_SAMPLES = 2

    /** Fra due osservazioni oltre questo tempo il backup non lavorava davvero (processo fermo, schermo spento): non si conta. */
    private const val MAX_STEP_MS = 30_000L

    /** D-11: le misure del riepilogo del primo backup (lo stesso modello, la stessa stima entro il 30%). */
    private val MEASURES: DeviceMeasures = DeviceMeasures.PHASE_0

    /** D-11: quanto lavoro osservato serve prima di tarare il modello sul ritmo vero. */
    private const val MIN_ACTIVE_MS = 10_000L

    /** D-11: la taratura resta fra un quarto e quattro volte il modello (un inizio lento o una raffica non stravolgono la stima). */
    private const val MIN_PACE = 0.25
    private const val MAX_PACE = 4.0
  }
}
