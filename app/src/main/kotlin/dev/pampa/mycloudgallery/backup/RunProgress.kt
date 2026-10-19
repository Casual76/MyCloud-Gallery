package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus

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
 */
internal class RunProgress {

  private var done = 0
  private var base = NONE
  private var bytesDone = 0L
  private var roundBytes = 0L
  private var activeMs = 0L
  private var activeDone = 0
  private var lastRunningAt: Long? = null
  private var changedSinceSnapshot = false

  /** Il progresso da mostrare per lo stato del motore [running] (di questo lotto), con [queued] media ancora in coda e l'ora monotona [nowMs]. */
  fun reshape(running: BackupStatus.Running, queued: Int, nowMs: Long): BackupStatus.Running {
    val previousTick = lastRunningAt
    if (base == NONE) {
      // L'inizio di una campagna (o di quello che se ne vede): i media del lotto gia' fatti contano, quelli di prima non si sanno.
      done = running.done
      bytesDone = running.bytesDone
      roundBytes = running.bytesDone
      activeMs = 0L
      activeDone = running.done
    } else {
      val delta = base - queued
      if (delta > 0) {
        done += delta
        if (previousTick != null) activeDone += delta
      }
      // I byte del lotto ripartono da zero a ogni lotto: si sommano le differenze, mai il valore del lotto intero.
      bytesDone += if (running.bytesDone >= roundBytes) running.bytesDone - roundBytes else running.bytesDone
      roundBytes = running.bytesDone
    }
    if (previousTick != null) activeMs += (nowMs - previousTick).coerceIn(0L, MAX_STEP_MS)
    lastRunningAt = nowMs
    base = queued
    changedSinceSnapshot = true

    val total = done + queued
    val eta = if (activeDone >= MIN_SAMPLES && activeMs > 0L && queued > 0) activeMs * queued / activeDone else null
    val bytesTotal = if (done > 0 && bytesDone > 0L) maxOf(bytesDone, bytesDone * total / done) else bytesDone + (running.bytesTotal - running.bytesDone).coerceAtLeast(0L)
    return running.copy(done = done, total = total, bytesDone = bytesDone, bytesTotal = bytesTotal, etaMillis = eta)
  }

  /**
   * Il backup non sta lavorando adesso. Senza niente in coda la campagna e' finita e si azzera; con altro da fare resta (e il ritmo non
   * conta il tempo fermo: la prossima tappa riparte da [reshape] senza un "tick" precedente).
   */
  fun idle(queued: Int) {
    if (queued == 0) {
      if (base != NONE || done != 0) changedSinceSnapshot = true
      reset()
    } else {
      lastRunningAt = null
    }
  }

  /** Il testo da salvare se qualcosa e' cambiato dall'ultima volta (null = niente da scrivere); [EMPTY] = togliere il file. */
  fun snapshot(): String? {
    if (!changedSinceSnapshot) return null
    changedSinceSnapshot = false
    return if (base == NONE && done == 0) EMPTY else listOf(done, base, bytesDone, roundBytes, activeMs, activeDone).joinToString(";")
  }

  /** Ripristina quello che [snapshot] aveva salvato (processo nuovo). Un testo illeggibile vale "niente". */
  fun restore(text: String?) {
    reset()
    val parts = text?.split(';')?.mapNotNull { it.toLongOrNull() } ?: return
    if (parts.size != 6 || parts.any { it < 0 && it != NONE.toLong() }) return
    done = parts[0].toInt()
    base = parts[1].toInt()
    bytesDone = parts[2]
    roundBytes = parts[3]
    activeMs = parts[4]
    activeDone = parts[5].toInt()
  }

  private fun reset() {
    done = 0
    base = NONE
    bytesDone = 0L
    roundBytes = 0L
    activeMs = 0L
    activeDone = 0
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
  }
}
