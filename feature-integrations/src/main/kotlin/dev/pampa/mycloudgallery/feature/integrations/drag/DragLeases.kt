package dev.pampa.mycloudgallery.feature.integrations.drag

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.pampa.mycloudgallery.core.library.contract.DragLease
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// I PRESTITI DEL TRASCINAMENTO (Fase 10, P6, D20, E-T14, L547; docs/fase10-progetto.md §2.5 "Fine"). Chi riceve un trascinamento legge **dopo** il rilascio, anche per
// un po' (un video di qualche GiB, una chat che carica in background): le copie temporanee senza posizione e gli id del provider **non si tolgono a fine gesto**. Vivono
// **finche' l'app e' in primo piano piu' 10 minuti** ([DragLimits.TEMP_GRACE_MS]): quando l'ultima finestra si ferma parte un conto alla rovescia, e se l'app torna davanti il
// conto si ferma. **In ogni caso non oltre il prossimo avvio** (`ShareCache.sweepAtStart` svuota `cacheDir/share/`) e non piu' di [MAX_HELD] trascinamenti insieme (i piu'
// vecchi escono per primi: le copie hanno un tetto di 2 GiB, un trascinamento dopo l'altro non lo riempie). Un trascinamento che **non parte** si rilascia subito (E-T17).

/**
 * I trascinamenti in corso (finiti, ma che l'altra app puo' ancora leggere). Una per processo; le chiamate arrivano dal thread principale (ciclo di vita delle activity,
 * gesto) e dai timer: e' sincronizzato. Il tempo e' quello delle coroutine (`delay`), mai l'ora del telefono (§4.3).
 *
 * @param scope la vita del processo: serve solo ai conti alla rovescia.
 * @param inForeground l'app e' in primo piano adesso? (di serie si'; [ForegroundTracker] lo tiene aggiornato).
 */
class DragLeases(
  private val scope: CoroutineScope,
  private val graceMs: Long = DragLimits.TEMP_GRACE_MS,
  private val maxHeld: Int = MAX_HELD,
  inForeground: Boolean = true,
) {

  private class Held(val release: () -> Unit) {
    var timer: Job? = null
    var done = false
  }

  private val lock = Any()
  private val held = ArrayList<Held>()
  private var foreground = inForeground

  /** Quanti trascinamenti sono ancora in prestito (per i test e la Diagnostica: solo un numero). */
  val count: Int get() = synchronized(lock) { held.size }

  /**
   * Mette in prestito un trascinamento: [release] toglie le sue copie e revoca i suoi id. Dà il [DragLease] che chi comincia il gesto rilascia **subito** se il
   * trascinamento non parte; rilasciare due volte e' innocuo.
   */
  fun hold(release: () -> Unit): DragLease {
    val entry = Held(release)
    val overflow = ArrayList<Held>()
    synchronized(lock) {
      held += entry
      while (held.size > maxHeld) overflow += held.removeAt(0)
      if (!foreground) entry.timer = countdown(entry)
    }
    overflow.forEach { finish(it, counted = true) }
    return DragLease { discard(entry) }
  }

  /** L'app va in secondo piano (`false`: parte il conto di [DragLimits.TEMP_GRACE_MS] per ogni prestito) o torna davanti (`true`: i conti si fermano). */
  fun foreground(on: Boolean) {
    synchronized(lock) {
      if (foreground == on) return
      foreground = on
      for (entry in held) {
        if (on) {
          entry.timer?.cancel()
          entry.timer = null
        } else if (entry.timer == null) {
          entry.timer = countdown(entry)
        }
      }
    }
  }

  /** Tutti subito: l'azzeramento dell'app, il cambio di famiglia, l'uscita. */
  fun releaseAll() {
    val all = synchronized(lock) { held.toList().also { held.clear() } }
    all.forEach { finish(it, counted = true) }
  }

  /** Il trascinamento non e' partito: via subito (e conta come non partito, non come finito). */
  private fun discard(entry: Held) {
    val mine = synchronized(lock) { held.remove(entry) }
    if (mine) finish(entry, counted = false)
    if (mine) DragCounters.add(DragCounter.OUT_DISCARDED)
  }

  private fun countdown(entry: Held): Job = scope.launch {
    delay(graceMs)
    val mine = synchronized(lock) { held.remove(entry) }
    if (mine) finish(entry, counted = true)
  }

  private fun finish(entry: Held, counted: Boolean) {
    synchronized(lock) {
      if (entry.done) return
      entry.done = true
    }
    entry.timer?.cancel()
    try {
      entry.release()
    } finally {
      if (counted) DragCounters.add(DragCounter.OUT_RELEASED)
    }
  }

  companion object {
    /** Quanti trascinamenti al piu' restano in prestito insieme. */
    const val MAX_HELD: Int = 4
  }
}

/**
 * Dice a [DragLeases] se l'app e' in primo piano: conta le activity avviate del processo (come il blocco dell'app, senza `ProcessLifecycleOwner`). Una finestra che si ferma
 * mentre l'altra resta visibile (due finestre, schermo diviso con un'altra app) non e' un'uscita. Si registra una volta, all'avvio del processo.
 */
class ForegroundTracker(private val leases: DragLeases) : Application.ActivityLifecycleCallbacks {

  private var started = 0

  fun attach(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
  }

  override fun onActivityStarted(activity: Activity) {
    started++
    leases.foreground(true)
  }

  override fun onActivityStopped(activity: Activity) {
    started = (started - 1).coerceAtLeast(0)
    // Una ricreazione (rotazione, densita') ferma e riavvia: non e' un'uscita, il conto parte solo se nessuna finestra e' visibile.
    if (started == 0 && !activity.isChangingConfigurations) leases.foreground(false)
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit
}
