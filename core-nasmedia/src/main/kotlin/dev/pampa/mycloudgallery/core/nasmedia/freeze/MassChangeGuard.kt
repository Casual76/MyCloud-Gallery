package dev.pampa.mycloudgallery.core.nasmedia.freeze

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeState
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeVerdict
import dev.pampa.mycloudgallery.core.nasmedia.contract.MassChangeRule
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Il CONGELAMENTO per cambiamenti di massa (docs/fase4-progetto.md D10 con la risposta Q6 dell'utente, §2.3; C11 di
// docs/sicurezza.md; E-S11, prova F9). Le soglie e i tipi sono di P0 (`contract/Freeze.kt`); qui il giudizio e lo stato.

/**
 * Il giudizio di D10. In una scansione completa di una share, **ciascuno** fra spariti, cambiati e "non piu' media" che
 * arriva ad almeno `max(50, 5% dei file presenti)` congela: alla soglia si congela, sotto no. Meglio fermarsi una volta
 * di troppo (ransomware, errori di massa).
 *
 * La ragione e' il conteggio piu' grave; a pari conteggio, spariti prima di non-piu'-media prima di cambiati.
 */
object MassChangeGuard {

  fun judge(diff: ScanDiff): FreezeVerdict {
    val threshold = MassChangeRule.threshold(diff.knownPresent)
    val tripped = listOf(
      FreezeReason.VANISHED to diff.vanished,
      FreezeReason.NOT_MEDIA to diff.notMedia,
      FreezeReason.CHANGED to diff.changed,
    ).filter { (_, count) -> count >= threshold }
    val worst = tripped.maxByOrNull { (_, count) -> count } ?: return FreezeVerdict.Proceed
    return FreezeVerdict.Freeze(worst.first, diff)
  }

  /** Vero se [diff] sta sotto tutte le soglie (si puo' scongelare da soli, D10). */
  fun isNormal(diff: ScanDiff): Boolean = judge(diff) is FreezeVerdict.Proceed
}

/**
 * Il cursore della scansione di una share, con **un solo scrittore alla volta**: lo scanner (avanzamento per cartella,
 * fine del giro, congelamento) e il gesto dell'utente che scongela ([StoreFreezeState.release]) possono arrivare insieme
 * e non devono sovrascriversi. Ogni modifica rilegge il cursore salvato, la applica e lo risalva sotto lo stesso lucchetto.
 */
class CursorKeeper(val store: NasScanStore, val account: AccountId, val share: String) {

  private val gate = Mutex()

  suspend fun read(): NasScanCursor = store.cursor(account, share) ?: NasScanCursor(account, share)

  suspend fun update(change: (NasScanCursor) -> NasScanCursor): NasScanCursor = gate.withLock {
    val next = change(read())
    store.saveCursor(next)
    next
  }
}

/**
 * Lo stato di congelamento di **una** share, salvato nel cursore della scansione (`NasScanCursor.freeze`, tabella
 * `nas_scan_state` di P2). Lo legge chi decide se scrivere sul NAS (`IndexWriteGate` composto da :app), i lavori
 * (`WaitReason.Frozen`), la Libreria e la Diagnostica; l'avviso va nel riepilogo giornaliero (Q6).
 *
 * Si scongela con [release] e un [UserGesture] il cui conteggio e' quello mostrato (la "doppia conferma"), oppure da solo
 * quando una scansione completa torna sotto le soglie (`DefaultNasScanner`). Un dato letto dal NAS non scongela mai.
 *
 * Dopo un [release] il giro successivo **accetta** la situazione che l'utente ha controllato: segna le assenze e registra
 * i file cambiati senza rigiudicarli (altrimenti la stessa situazione congelerebbe di nuovo all'infinito).
 */
class StoreFreezeState(private val keeper: CursorKeeper) : FreezeState {

  constructor(store: NasScanStore, account: AccountId, share: String) : this(CursorKeeper(store, account, share))

  private val state = MutableStateFlow<Freeze?>(null)
  private val accepted = AtomicBoolean(false)

  override val frozen: StateFlow<Freeze?> = state.asStateFlow()

  /** Legge lo stato salvato (all'avvio, prima del primo giro: la Libreria deve poter mostrare un congelamento di ieri). */
  suspend fun load() {
    state.value = keeper.read().freeze
  }

  override suspend fun release(gesture: UserGesture): Boolean {
    val current = keeper.read().freeze ?: return false
    if (gesture.confirmedCount != current.headlineCount) return false
    var released = false
    keeper.update { cursor ->
      // Riletto sotto lucchetto: un giro puo' aver aggiornato il congelamento nel frattempo.
      if (cursor.freeze?.headlineCount == gesture.confirmedCount) {
        released = true
        cursor.copy(freeze = null)
      } else {
        cursor
      }
    }
    if (released) {
      accepted.set(true)
      state.value = null
    }
    return released
  }

  /** Lo scanner pubblica qui il congelamento deciso a fine giro (il cursore l'ha gia' salvato). */
  internal fun publish(freeze: Freeze?) {
    state.value = freeze
  }

  /** Vero una sola volta dopo un gesto valido: il prossimo giro completo non rigiudica. */
  internal fun consumeAcceptance(): Boolean = accepted.getAndSet(false)

  /** C'e' un gesto non ancora consumato (il giro che lo consuma puo' essere interrotto e ripreso). */
  internal fun hasAcceptance(): Boolean = accepted.get()

  internal fun restoreAcceptance() {
    accepted.set(true)
  }
}
