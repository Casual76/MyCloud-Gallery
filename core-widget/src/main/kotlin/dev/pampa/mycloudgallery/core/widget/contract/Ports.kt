package dev.pampa.mycloudgallery.core.widget.contract

import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDateTime
import kotlinx.coroutines.flow.StateFlow

// LE PORTE DEL WIDGET (docs/fase10-progetto.md §1.2, §1.4 "WidgetGraph"). Le lega :app (P13): scaffale di `MemoriesGraph`, miniature locali
// di core-imaging, blocco dell'app e presenza sul telefono, DataStore locale, orologio. I null-object (noop/) falliscono **chiusi**: nessuna
// foto, blocco acceso, nessun ricordo.

/**
 * Il ricordo di oggi come lo vede il widget (D4): gia' scelto con le regole della notifica (F9-D15: pubblicato oggi, non scartato, mai
 * `PERSON`, mai una persona con "Mostra meno" in copertina), con il titolo **come dati senza persone**. [members] in ordine, [cover] uno di
 * loro. Il pianificatore ricontrolla tutto (tipo, nascosti, visibili, miniature): la porta non e' l'unica difesa.
 */
data class WidgetMemoryPick(
  val id: MemoryId,
  val kind: MemoryKind,
  val title: WidgetTitle,
  val cover: MediaId,
  val members: List<MediaId>,
) {
  init {
    require(cover in members) { "La copertina e' uno degli elementi." }
    require(members.distinct().size == members.size) { "Elementi ripetuti." }
  }

  override fun toString(): String = "WidgetMemoryPick(${kind.code}, ${members.size} elementi)"
}

/** Lo scaffale per il widget: il ricordo di oggi o null. Un errore di lettura = null (si passa alle foto, mai un ricordo non verificato). */
fun interface WidgetMemories {
  suspend fun today(): WidgetMemoryPick?
}

/**
 * Le miniature **locali** (D7, C431): quali di [ids] hanno la miniatura sul telefono (replica di un media solo-NAS o miniatura del telefono).
 * Mai il NAS, mai la rete: un media senza miniatura locale si salta (E-W5).
 */
fun interface WidgetThumbs {
  suspend fun local(ids: Collection<MediaId>): Set<MediaId>
}

/**
 * Il blocco dell'app (D8, Q3 (c)): con [appLocked] vero il widget mostra solo i media di [onPhone] (anche sul telefono: quelli che Galleria
 * Samsung mostra gia' a chi ha il telefono sbloccato) e nessun titolo. [onPhone] su un errore risponde vuoto (fallire chiusi).
 */
interface WidgetLock {
  val appLocked: StateFlow<Boolean>

  suspend fun onPhone(ids: Collection<MediaId>): Set<MediaId>
}

/** Le scelte del widget, per dispositivo e profilo (DataStore locale, mai sincronizzate): Q1 e Q2. Q3 (c) non ha una scelta. */
interface WidgetPrefs {
  val choice: StateFlow<WidgetChoice>
  val period: StateFlow<WidgetPeriod>

  suspend fun setChoice(choice: WidgetChoice)

  suspend fun setPeriod(period: WidgetPeriod)
}

/**
 * L'ora per il widget (§4.3 "Ora"): [nowLocal] l'ora **locale** per le ore di quiete (D6) e il giorno del ricordo; [monotonicMs] un orologio
 * monotono in millisecondi per budget e calma (mai l'ora da muro per le durate).
 */
interface WidgetClock {
  fun nowLocal(): LocalDateTime

  fun monotonicMs(): Long
}

/**
 * Il pianificatore (D3-D8, §2.1). Per un widget di misura [size] decide il piano entro [budgetMs] (monotono): contenuto secondo
 * `WidgetPrefs.choice`, blocco dell'app, miniature locali, **filtro finale** (`VisibleMedia.visibleOrThrow` e `HiddenMask.hiddenAmongOrThrow`
 * sugli id e sulla copertina) come ultimo passo. Un errore o il budget superato = `WidgetMode.Message(WidgetEmpty.ERROR)`: mai una lista non
 * verificata. Esclusi screenshot, documenti e immagini animate (come i ricordi, F9-D5); mai un ricordo `PERSON`.
 */
interface WidgetPlanner {
  suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long = WidgetRules.BUDGET_MS): WidgetPlan
}
