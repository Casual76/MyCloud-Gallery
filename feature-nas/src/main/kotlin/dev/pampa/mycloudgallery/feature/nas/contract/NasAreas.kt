package dev.pampa.mycloudgallery.feature.nas.contract

import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Le aree del NAS che le schermate della 4B mostrano (docs/fase4-progetto.md §1.4, §2.7). Le compone `:app` (P16) da
// `LocalDeviceProfile.privateShare` e dal registro accettato (l'area comune): qui sono solo due nomi di share e il loro genere.
// Niente di tutto questo viene dal NAS in questo momento: sono scelte fatte all'onboarding e firmate dal registro.

/** Di chi e' un'area: solo di chi ha l'account, o di tutta la famiglia. */
enum class NasAreaKind {
  /** L'area privata dell'account: la vede solo lui. */
  PRIVATE,

  /** L'area comune: la vedono (e, con il permesso, la cambiano) tutti i familiari. */
  COMMON,
}

/**
 * Un'area del NAS: la share e il genere. [share] e' un dato dell'utente (mai nei log).
 *
 * @property label il nome da mostrare se `:app` ne vuole uno diverso da "La mia area" / "Area comune" (per esempio "Famiglia").
 */
data class NasArea(val share: String, val kind: NasAreaKind, val label: String? = null) {
  override fun toString(): String = "NasArea(${kind.name})"
}

/** Le aree dell'account su questo dispositivo, nell'ordine in cui si mostrano (prima la privata). */
interface NasAreas {
  val areas: StateFlow<List<NasArea>>

  /**
   * R11 (revisione 4B): quanti elementi di [plan] (uno spostamento) l'utente ha **nascosto** nella Libreria. Lo sa `:app` (catalogo e dati
   * personali), non le schermate: il foglio di uno spostamento nell'area comune lo dice, perche' i familiari non sanno cosa ha nascosto.
   * Solo da mostrare: non cambia il piano.
   */
  suspend fun hiddenAmong(plan: OpPlan): Int = 0

  /**
   * R11, scelta dell'utente del 15/10 ("Lascia i nascosti dove sono"): **quali** elementi di [plan] (gli indici di `plan.items`) sono
   * nascosti. Con questi la schermata chiede il piano che li lascia dov'erano. Sono solo i file che cambiano area (un elemento
   * `MOVE_ACROSS_SHARES`): spostare dentro la stessa area non mostra niente a nessuno in piu'.
   *
   * L'implementazione di serie ricava la risposta da [hiddenAmong] su pezzi del piano, con una ricerca a meta' (pochi nascosti, poche
   * domande): `:app` puo' rispondere direttamente con una sola ricerca nel catalogo. Come [hiddenAmong], viene solo dai dati personali
   * dell'utente su questo telefono: mai da cio' che si legge dal NAS.
   */
  suspend fun hiddenItems(plan: OpPlan): Set<Int> = HiddenSearch.find(plan, ::hiddenAmong)

  /**
   * NA-10 (E-N15): le cartelle dell'**area privata** che l'utente ha escluso dalla Libreria. E' un'impostazione personale sincronizzata
   * fra i dispositivi dell'account (Q9): le legge la scansione a ogni giro. Escludere non cancella ne' sposta niente sul NAS: le foto
   * della cartella escono dalla Libreria e tornano se la cartella si include di nuovo.
   */
  val privateExclusions: StateFlow<Set<SharePath>> get() = NoExclusions

  /**
   * NA-10: imposta le cartelle escluse (tutte, non una alla volta). Vero se e' stato salvato; falso se non si puo' (nessun NAS, nessuna
   * area privata). Chiede anche una verifica del NAS, cosi' la Libreria si aggiorna senza aspettare il lavoro della notte.
   */
  suspend fun setPrivateExclusions(folders: Set<SharePath>): Boolean = false

  /**
   * NA-11 (D32): di quanto e' saltata l'ora del NAS (ms, col segno), o null se non c'e' un salto che sospende le scritture. Finche' c'e'
   * il salto, indice e cestino aspettano che l'ora torni stabile per 24 ore.
   */
  val clockJumpMs: StateFlow<Long?> get() = NoClockJump

  /**
   * NA-11: il gesto "L'ora del NAS e' giusta, continua": la sospensione finisce subito e il cestino riparte da adesso. Viene solo dalla
   * UI, dopo un tocco di conferma, mai da un dato letto dal NAS. Falso se non c'era un salto.
   */
  fun acceptClockJump(): Boolean = false
}

private val NoExclusions: StateFlow<Set<SharePath>> = MutableStateFlow(emptySet())

private val NoClockJump: StateFlow<Long?> = MutableStateFlow(null)

/** La ricerca dei nascosti fra gli elementi di un piano a partire da "quanti ce ne sono" in un gruppo di elementi. */
internal object HiddenSearch {

  suspend fun find(plan: OpPlan, count: suspend (OpPlan) -> Int): Set<Int> {
    val candidates = plan.items.filter { !it.isFolder && it.flow == ItemFlow.MOVE_ACROSS_SHARES }
    if (candidates.isEmpty()) return emptySet()
    val total = count(partOf(plan, candidates))
    if (total <= 0) return emptySet()
    val found = LinkedHashSet<Int>()
    search(plan, candidates, count, found)
    return found
  }

  /** Cerca nel [group], che ha almeno un nascosto: lo divide a meta' finche' resta un elemento solo. */
  private suspend fun search(plan: OpPlan, group: List<PlannedItem>, count: suspend (OpPlan) -> Int, found: MutableSet<Int>) {
    if (group.size == 1) {
      found += group.single().index
      return
    }
    val left = group.subList(0, group.size / 2)
    val right = group.subList(group.size / 2, group.size)
    val inLeft = count(partOf(plan, left))
    if (inLeft > 0) search(plan, left, count, found)
    // Se a sinistra non ce n'e', ce n'e' a destra (il gruppo ne ha almeno uno); se ce n'e' si conta anche a destra, senza fidarsi di una
    // sottrazione: un nascosto mancato finirebbe nell'area comune.
    if (inLeft <= 0 || count(partOf(plan, right)) > 0) search(plan, right, count, found)
  }

  /** Un piano con soli [items] del piano di partenza, per chiedere "quanti nascosti ci sono qui". */
  private fun partOf(plan: OpPlan, items: List<PlannedItem>): OpPlan {
    val summary = PlanSummary(plan.kind, items.size, 0, items.sumOf { it.size }, plan.summary.destShare, plan.summary.destFolder)
    return OpPlan(plan.kind, items, emptyList(), summary, emptySet())
  }
}

/** Quale area e' [share], se e' fra [areas] (il confronto non distingue le maiuscole, come il NAS). */
fun List<NasArea>.areaOf(share: String): NasArea? = firstOrNull { it.share.equals(share, ignoreCase = true) }
