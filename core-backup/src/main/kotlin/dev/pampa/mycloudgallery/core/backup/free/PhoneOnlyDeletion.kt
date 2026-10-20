package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode

// Fase 5B, P9 (docs/fase5-progetto.md D22, E-D1, E-D14, E-B14, §2.3 "Elimina solo dal telefono"): "ELIMINA SOLO DAL TELEFONO". Il NAS non si
// tocca; la copia del telefono se ne va **solo** come in "Libera spazio" (guardiano, verifica sul momento, controllo del timbro, dialogo di
// sistema): nessun media resta senza una copia sotto il controllo del proprietario (5.3). Per un media non al sicuro il foglio avvisa e
// propone "Prima mettilo al sicuro", **preselezionata** (E-D14): backup e verifica, poi il cestino di sistema.

/** Che cosa propone il foglio di "Elimina solo dal telefono" per un media. In ordine di gravita' crescente. */
enum class PhoneOnlyAdvice(override val code: String) : StableCode {
  /** Al sicuro: si libera come "Libera spazio" (verifica sul momento, timbro, cestino di sistema). */
  FREE("free"),

  /** E-B14: c'e' solo la copia dell'area comune: prima il guardiano crea la copia privata (D9), poi come [FREE]. */
  GUARD_FIRST("guard_first"),

  /** E-D14: non e' al sicuro (o la sua copia non e' verificata): avviso, "Prima mettilo al sicuro" preselezionata. */
  SAFE_FIRST("safe_first"),

  /** In caricamento, o con un problema del backup: aspetta (il problema ha la sua azione nella pagina Backup). */
  WAIT("wait"),

  /** E-B22: area privata congelata o scritture ferme: impossibile finche' dura. */
  FROZEN("frozen"),

  /** "Libera spazio" non c'e' (nessuna area privata, backup non attivo, D12 o R2): niente si toglie dal telefono. */
  UNAVAILABLE("unavailable"),

  /** D13: in debug con un account di prova, fuori da `mcg-prova` non si tocca niente. */
  OUTSIDE_TEST_FENCE("outside_test_fence"),

  /** Il media non ha una copia sul telefono: non c'e' niente da eliminare dal telefono. */
  NOT_ON_PHONE("not_on_phone"),
}

/**
 * L'esito per una scelta di media (D22). [advice] per media; [preselectSafeFirst] dice se il foglio preseleziona "Prima mettilo al sicuro"
 * (E-D14: almeno un media non al sicuro); [freeFilter] e' il filtro con cui chiedere a `FreeSpace.plan` il piano dei media che si possono
 * liberare adesso (gia' al sicuro o con il guardiano): **nessun giorno escluso** (li ha scelti l'utente) e al massimo quei media.
 */
class PhoneOnlyAssessment(val advice: Map<MediaId, PhoneOnlyAdvice>) {

  /** E-D14: con un media non al sicuro la scelta preselezionata e' "Prima mettilo al sicuro". */
  val preselectSafeFirst: Boolean get() = advice.values.any { it == PhoneOnlyAdvice.SAFE_FIRST }

  /** I media da mettere prima al sicuro (backup e verifica: `:app` avvia "Fai ora" e, finito, ripropone il foglio). */
  val toSafeguard: Set<MediaId> get() = advice.filterValues { it == PhoneOnlyAdvice.SAFE_FIRST }.keys

  /**
   * LS-1 (decisione dell'utente del 20/10, "Elimina con avviso"): i media che **non risultano al sicuro sul NAS** (backup non attivo, nessuna
   * copia privata verificata, in caricamento o con un problema, area ferma, fuori dalla cartella di prova). Per loro il viewer offre il cestino
   * di Android (30 giorni) **dopo un avviso** e una conferma in piu'; per quelli al sicuro vale il flusso della 5B ([freeFilter]).
   */
  val notOnNas: Set<MediaId> get() = advice.filterValues { it in NOT_ON_NAS }.keys

  /** Il filtro del piano per i media liberabili adesso, o null se non ce ne sono. */
  val freeFilter: FreeSpaceFilter?
    get() {
      val ready = advice.filterValues { it == PhoneOnlyAdvice.FREE || it == PhoneOnlyAdvice.GUARD_FIRST }.keys
      return if (ready.isEmpty()) null else FreeSpaceFilter(kind = FreeSpaceKind.ALL, excludeRecentDays = 0, selection = ready)
    }

  override fun toString(): String = "PhoneOnlyAssessment(${advice.size} media)"

  internal companion object {
    /** LS-1: i consigli di un media che non risulta al sicuro sul NAS. */
    private val NOT_ON_NAS: Set<PhoneOnlyAdvice> = setOf(
      PhoneOnlyAdvice.SAFE_FIRST,
      PhoneOnlyAdvice.WAIT,
      PhoneOnlyAdvice.FROZEN,
      PhoneOnlyAdvice.UNAVAILABLE,
      PhoneOnlyAdvice.OUTSIDE_TEST_FENCE,
    )

    /** Il consiglio per un media dagli esiti delle sue copie del telefono (il piu' grave vince). */
    fun adviceOf(outcomes: List<CopyOutcome>, unavailable: Boolean): PhoneOnlyAdvice {
      if (outcomes.isEmpty()) return PhoneOnlyAdvice.NOT_ON_PHONE
      if (unavailable) {
        return if (outcomes.any { it is CopyOutcome.Excluded && it.reason == FreeSpaceExclusion.OUTSIDE_TEST_FENCE }) {
          PhoneOnlyAdvice.OUTSIDE_TEST_FENCE
        } else {
          PhoneOnlyAdvice.UNAVAILABLE
        }
      }
      return outcomes.maxOf { outcome ->
        when (outcome) {
          is CopyOutcome.Ready -> if (outcome.guardVersions.isEmpty()) PhoneOnlyAdvice.FREE else PhoneOnlyAdvice.GUARD_FIRST
          is CopyOutcome.Excluded -> when (outcome.reason) {
            FreeSpaceExclusion.OUTSIDE_TEST_FENCE -> PhoneOnlyAdvice.OUTSIDE_TEST_FENCE
            FreeSpaceExclusion.AREA_FROZEN -> PhoneOnlyAdvice.FROZEN
            FreeSpaceExclusion.IN_PROGRESS_OR_ERROR, FreeSpaceExclusion.AWAITING_PHONE -> PhoneOnlyAdvice.WAIT
            FreeSpaceExclusion.NAS_UNREACHABLE, FreeSpaceExclusion.NOT_MINE, FreeSpaceExclusion.BACKUP_NOT_ACTIVE -> PhoneOnlyAdvice.UNAVAILABLE
            FreeSpaceExclusion.NOT_VERIFIED, FreeSpaceExclusion.VERSION_NOT_SAFE, FreeSpaceExclusion.RECENT,
            FreeSpaceExclusion.COPY_CHANGED, FreeSpaceExclusion.GONE_FROM_PHONE,
            -> PhoneOnlyAdvice.SAFE_FIRST
          }
        }
      }
    }
  }
}
