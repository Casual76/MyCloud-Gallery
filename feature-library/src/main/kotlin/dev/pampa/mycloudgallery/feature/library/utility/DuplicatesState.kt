package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarCluster
import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 7, P8 (D25, D27; docs/fase7-progetto.md §1.5 `UtilityState`: "Doppioni: Scanning, Groups(identical, similar), Cleanup(plan)"): gli STATI della pagina
// Doppioni, senza Compose a parte l'annotazione. Il ViewModel li consegna, le schermate li disegnano; il filone grafico rifinisce senza toccare la logica.

/** Le due schede dei Doppioni (§2.1.1). */
enum class DuplicatesTab(val code: String) {
  IDENTICAL("identical"),
  SIMILAR("similar"),
}

/** I doppioni identici: la lettura (in corso, fallita) e i gruppi con la loro proposta, con le scelte "Rivedi" gia' applicate. */
@Immutable
data class IdenticalView(
  val scanning: Boolean = true,
  val failed: Boolean = false,
  val groups: List<GroupProposal> = emptyList(),
  /** DP-1: insiemi di file del NAS che sembrano uguali e aspettano l'hash completo: non sono ancora doppioni provati, e la pagina lo dice. */
  val verifying: Int = 0,
  /** UT-1: con l'accesso alle sole foto selezionate (o prima che la Libreria sia letta) i doppioni non si cercano: nessun gruppo, e la pagina dice perche'. */
  val restricted: Boolean = false,
) {
  /** I gruppi con qualcosa da togliere. */
  val cleanable: List<GroupProposal> get() = groups.filter { it.status == IdenticalStatus.CLEANABLE }

  /** I gruppi non ancora provati identici (D27): niente da togliere finche' `FULL_HASH` non decide. */
  val toVerify: List<GroupProposal> get() = groups.filter { it.status == IdenticalStatus.TO_VERIFY }

  /** I gruppi dove le regole "mai" lasciano tutto com'e' (una sola copia con il permesso, o copie protette). */
  val settled: List<GroupProposal> get() = groups.filter { it.status == IdenticalStatus.NOTHING_TO_REMOVE }

  val copiesToRemove: Int get() = cleanable.sumOf { it.remove.size }

  val reclaimableBytes: Long get() = cleanable.sumOf { it.reclaimableBytes }

  /** Quante copie stanno nell'area di un altro account in tutti i gruppi (E-U1): non si propongono, si dicono. */
  val inOthersArea: Int get() = groups.sumOf { it.inOthersArea }

  val isEmpty: Boolean get() = !scanning && !failed && !restricted && verifying == 0 && groups.isEmpty()
}

/** Un gruppo di scatti simili con la scelta di adesso: [remove] sono quelli da eliminare, gli altri restano. */
@Immutable
data class ClusterView(val cluster: SimilarCluster, val remove: Set<MediaId>) {
  val keep: List<MediaId> get() = cluster.media.filter { it !in remove }

  /** Fra gli scatti da eliminare, quanti sono preferiti o in un album: l'utente li ha scelti lui (E-U3), si avvisa. */
  val protectedChosen: List<MediaId> get() = remove.filter { cluster.details[it]?.isProtected == true }
}

/** I doppioni simili: la ricerca (a lotti, dal giorno piu' recente) e i gruppi trovati finora. */
@Immutable
data class SimilarView(
  val scanning: Boolean = true,
  val failed: Boolean = false,
  val clusters: List<ClusterView> = emptyList(),
  /** Scatti con compagni vicini che non si sono potuti giudicare (nessun vettore, nessuna firma): la pagina lo dice. */
  val undecided: Int = 0,
  /** DP-4: alcune foto recenti non hanno ancora la firma visiva (la fa la manutenzione di notte, con il telefono in carica): la pagina lo dice invece di restare muta. */
  val signingLater: Boolean = false,
) {
  /** Quanti scatti la pulizia di tutti i gruppi manderebbe nel cestino. */
  val toRemove: Int get() = clusters.sumOf { it.remove.size }

  val isEmpty: Boolean get() = !scanning && !failed && clusters.isEmpty()
}

/** L'interruttore dell'hash completo notturno (D27): null = la pagina non lo mostra (nessuna porta legata). */
@Immutable
data class NightlyView(val available: Boolean = false, val on: Boolean = false)

/** Da dove nasce una pulizia: i doppioni identici (D25) o gli scatti simili scelti dall'utente. */
enum class CleanupSource { IDENTICAL, SIMILAR }

/** La scelta del luogo del foglio di eliminazione esistente ("Solo dal telefono" / "Ovunque"). */
enum class CleanupScope { PHONE_ONLY, EVERYWHERE }

/**
 * Il foglio di eliminazione in corso (§2.1.1: "il foglio di eliminazione esistente"): il piano per ogni luogo, cosi' il foglio mostra l'effetto della
 * scelta *prima* di premere. [scopes] sono le scelte possibili (una sola per i doppioni identici). Niente parte finche' l'utente non conferma.
 *
 * @property othersAreas le share delle copie nell'area di un altro account che la pulizia non tocca (E-U1): una per copia, null se non si sa.
 * @property phoneLeft copie del telefono oltre il tetto di una richiesta di sistema: restano per un altro giro.
 */
@Immutable
data class CleanupSheet(
  val source: CleanupSource,
  val plans: Map<CleanupScope, CleanupPlan>,
  val chosen: CleanupScope,
  val othersAreas: List<String?> = emptyList(),
  val phoneLeft: Int = 0,
) {
  init {
    require(plans.isNotEmpty() && chosen in plans) { "La scelta e' uno dei luoghi del piano." }
  }

  val scopes: List<CleanupScope> get() = plans.keys.toList()

  val plan: CleanupPlan get() = plans.getValue(chosen)

  val count: Int get() = plan.copies.coerceAtLeast(1)
}

/** Cosa e' successo all'ultimo gesto: una riga in cima, si chiude con la X. */
enum class DuplicatesNotice {
  /** Una pulizia sul NAS e' partita: si segue in "Operazioni in corso". */
  CLEANUP_STARTED,

  /** Il NAS non ha accettato la pulizia (copie cambiate, share in sola lettura): non e' stato toccato niente. */
  CLEANUP_REJECTED,

  /** Le scritture sul NAS sono ferme adesso. */
  CLEANUP_BLOCKED,

  /** Non e' riuscito. */
  CLEANUP_FAILED,

  /** Lo scatto non si toglie: in un gruppo almeno uno resta. */
  LAST_SHOT_STAYS,

  /** Il piano e' vuoto: tutte le copie sono cambiate o sparite nel frattempo. */
  NOTHING_TO_CLEAN,

  /** Il telefono non ha il cestino di sistema (prima di Android 11): le copie del telefono non si possono togliere da qui. */
  NO_PHONE_TRASH,

  /** R18: alcune copie del telefono non sono piu' quelle del piano (sovrascritte o spostate dopo la lettura): non sono state mandate nel cestino. */
  PHONE_CHANGED,

  /** DP-5: l'interruttore dell'hash completo di notte non si e' potuto salvare (i dati personali non sono ancora pronti): resta com'era. */
  NIGHTLY_NOT_SAVED,
}

/** Lo stato di tutta la pagina Doppioni. */
@Immutable
data class DuplicatesState(
  val tab: DuplicatesTab = DuplicatesTab.IDENTICAL,
  val identical: IdenticalView = IdenticalView(),
  val similar: SimilarView = SimilarView(),
  val nightly: NightlyView = NightlyView(),
  val sheet: CleanupSheet? = null,
  val notice: DuplicatesNotice? = null,
  /** Una richiesta del dialogo di sistema o del NAS e' in corso: i comandi di pulizia stanno fermi. */
  val busy: Boolean = false,
  /**
   * DP-7: la richiesta del dialogo di sistema per le copie del telefono, pronta da lanciare. Sta nello stato e non nel valore di ritorno di `confirm()`:
   * se l'attivita' si ricrea mentre il NAS lavora (una rotazione) la composizione che chiamava e' sparita, ma la richiesta aspetta quella nuova.
   */
  val phoneRequest: android.content.IntentSender? = null,
)
