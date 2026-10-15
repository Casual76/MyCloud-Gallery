package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

// Fase 4B (docs/fase4-progetto.md §2.7): le regole PURE delle operazioni, senza NAS e senza orologio: tetti, nomi, temporanei, attese per
// i file bloccati e i controlli che ogni piano deve passare prima di toccare il NAS. Le usano P12 (pianificazione ed esecuzione), il fake e
// il banco delle 500 interruzioni.

object OpRules {

  /** Elementi per operazione: una mossa di 2.000 file e' normale, oltre si chiede all'utente di dividere. */
  const val MAX_ITEMS_PER_OP: Int = 10_000

  /** Prima di uno spostamento tra share: spazio libero della destinazione >= dimensione + 1 GiB (E-D5). */
  const val MIN_FREE_MARGIN_BYTES: Long = 1L shl 30

  fun requiredFreeBytes(totalBytes: Long): Long = totalBytes + MIN_FREE_MARGIN_BYTES

  /** Le operazioni chiuse che "Operazioni in corso" tiene ancora in elenco. */
  const val CLOSED_OPS_SHOWN: Int = 20
}

/** I temporanei di un'operazione: nomi derivati da id, mai da testo letto dal NAS. */
object OpTemps {

  /**
   * Il temporaneo di un elemento di spostamento tra share: `.mycloudgallery/tmp/<dispositivo>-op-<operazione>-<indice>`
   * (`OwnFile.Temp` di core-trust, quindi lo riconosce anche la pulizia dei propri file, che lo lascia finche' il diario lo cita).
   */
  fun tempFor(deviceId: String, opId: OpId, index: Int): SharePath {
    require(index >= 0) { "Indice negativo." }
    return NasLayout.ROOT.child("tmp").child("$deviceId-op-${opId.value}-$index")
  }

  /** Il nome temporaneo della rinomina solo di maiuscole (E-N7): nella stessa cartella dell'elemento, fuori dall'area dell'app. */
  fun caseSwapName(source: SharePath, opId: OpId, index: Int): SharePath {
    require(index >= 0) { "Indice negativo." }
    val name = ".mcg-swap-${opId.value}-$index"
    return source.parent()?.child(name) ?: SharePath.of(name)
  }
}

/** I nomi di file e cartelle che l'utente scrive (E-D9) e quelli alternativi quando la destinazione e' occupata. */
object NameRules {

  sealed interface Check {
    data object Valid : Check

    /** Non va bene; [suggestion] e' la correzione proposta (caratteri vietati sostituiti, spazi finali tolti), o null se non c'e'. */
    data class Invalid(val suggestion: String?) : Check
  }

  fun check(name: String): Check {
    if (SharePath.isValidComponent(name)) return Check.Valid
    return Check.Invalid(suggest(name))
  }

  /** La correzione proposta per [raw], o null se non ne esiste una valida. */
  fun suggest(raw: String): String? {
    val cleaned = raw.trim().map { if (it in FORBIDDEN_CHARS || it.code < SPACE) '_' else it }.joinToString("").trimEnd(' ', '.')
    if (cleaned.isEmpty() || cleaned == raw) return null
    return cleaned.takeIf { SharePath.isValidComponent(it) }
  }

  /**
   * Il nome alternativo numero [attempt] (da 2) quando [name] e' occupato nella destinazione: `foto.jpg` diventa `foto (2).jpg`. La
   * scelta si scrive nel diario prima di riprovare (§2.7), cosi' una ripresa non ne sceglie un'altra.
   */
  fun alternative(name: String, attempt: Int): String {
    require(attempt >= 2) { "I nomi alternativi partono da (2)." }
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name ($attempt)" else "${name.substring(0, dot)} ($attempt)${name.substring(dot)}"
  }

  private const val FORBIDDEN_CHARS = "\\/:*?\"<>|"
  private const val SPACE = 32
}

/** Le attese crescenti per un file aperto dal PC (E-N13): si riprova fino a 24 ore, poi l'elemento si ferma. */
object LockedBackoff {

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 60L * MINUTE_MS

  /** Dopo il 1°, 2°, ... tentativo: 1, 2, 5, 15, 30 minuti, poi 1, 2, 4, 8, 12 ore (l'ultima si accorcia per fermarsi a 24 ore). */
  val DELAYS_MS: List<Long> =
    listOf(1L, 2, 5, 15, 30).map { it * MINUTE_MS } + listOf(1L, 2, 4, 8, 12).map { it * HOUR_MS }

  const val MAX_TOTAL_WAIT_MS: Long = 24L * HOUR_MS

  sealed interface Decision {
    data class RetryAfter(val delayMs: Long) : Decision

    /** Sono passate 24 ore: l'elemento si ferma con [OpErrorCode.LOCKED_TOO_LONG] e lo dice. */
    data object GiveUp : Decision
  }

  /** Cosa fare dopo che l'elemento e' risultato bloccato per la [attempts]-esima volta ([attempts] da 0). */
  fun decide(attempts: Int): Decision {
    require(attempts >= 0) { "Tentativi negativi." }
    val waited = DELAYS_MS.take(attempts).sum()
    val remaining = MAX_TOTAL_WAIT_MS - waited
    val delay = DELAYS_MS.getOrNull(attempts)
    if (delay == null || remaining <= 0) return Decision.GiveUp
    return Decision.RetryAfter(minOf(delay, remaining))
  }
}

/**
 * Le attese **brevi** per una cartella che il telefono stesso tiene aperta (decisione dell'utente, 15/10): la Libreria sta leggendo un file
 * dentro la cartella che si sposta o si elimina, e Samba non rinomina una cartella con un file aperto sotto dallo stesso client (`ACCESS_DENIED`).
 * Non e' un file aperto dal PC ([LockedBackoff]) e non e' un permesso negato: i nostri handle si rilasciano e si riprova dopo 1, 2, 4, 8, 16 e
 * 32 secondi, circa un minuto in tutto, senza uscire dal giro dell'operazione. Se dopo l'ultima prova la cartella e' ancora bloccata
 * l'elemento diventa un'attesa normale ([LockedBackoff], "aperto"), mai un errore definitivo.
 */
object OwnFolderBackoff {

  /** Le attese fra una prova e la successiva. */
  val DELAYS_MS: List<Long> = listOf(1L, 2, 4, 8, 16, 32).map { it * 1_000L }

  /** Quanto si aspetta, al massimo, prima di passare all'attesa normale. */
  val MAX_TOTAL_WAIT_MS: Long = DELAYS_MS.sum()
}

/** Cosa non va in un piano. Un piano che ne ha una non parte, anche se la conferma e' valida. */
enum class PlanViolation(override val code: String) : StableCode {
  EMPTY("empty"),
  TOO_MANY_ITEMS("too_many_items"),
  BAD_INDEX("bad_index"),

  /** Un percorso di media dentro `.mycloudgallery/`: mai un'operazione sui file dell'app. */
  APP_AREA_PATH("app_area_path"),
  MISSING_DESTINATION("missing_destination"),
  SAME_LOCATION("same_location"),
  INTO_ITSELF("into_itself"),

  /** Il cammino dell'elemento non e' quello che il tipo di operazione prevede. */
  WRONG_FLOW("wrong_flow"),

  /** "Elimina subito" di qualcosa che non sta nel cestino di questo dispositivo, o con un id che non corrisponde. */
  NOT_OWN_TRASH("not_own_trash"),

  /** Un ripristino da un posto che non e' un file nel cestino. */
  NOT_A_TRASH_ITEM("not_a_trash_item"),

  /** R11: un file che il piano dice di lasciare dov'e' (un nascosto) compare anche fra gli elementi da spostare. */
  LEFT_BEHIND_MOVED("left_behind_moved"),
}

/**
 * I controlli che `NasOperations.start` fa su un piano **prima** di scriverlo nel diario, anche con una conferma valida: il piano
 * puo' venire da una UI con un errore, e un piano sbagliato non deve diventare un'operazione sul NAS.
 */
object PlanRules {

  /** La prima violazione del [plan] per il dispositivo [ownerDeviceId] (proprietario del cestino proprio), o null se e' in ordine. */
  fun firstViolation(plan: OpPlan, ownerDeviceId: String): PlanViolation? {
    if (plan.items.isEmpty()) return PlanViolation.EMPTY
    if (plan.items.size > OpRules.MAX_ITEMS_PER_OP) return PlanViolation.TOO_MANY_ITEMS
    plan.items.forEachIndexed { at, item ->
      if (item.index != at) return PlanViolation.BAD_INDEX
      itemViolation(plan.kind, item, ownerDeviceId)?.let { return it }
    }
    // R11: cio' che il piano elenca come "lasciato dov'e'" non puo' essere anche un elemento da spostare (un nascosto nell'area comune).
    val left = plan.leftBehind
    if (left.isNotEmpty()) {
      val keys = left.mapTo(HashSet()) { it.share.lowercase(Locale.ROOT) + "/" + it.path.value.lowercase(Locale.ROOT) }
      if (plan.items.any { (it.srcShare.lowercase(Locale.ROOT) + "/" + it.srcPath.value.lowercase(Locale.ROOT)) in keys }) {
        return PlanViolation.LEFT_BEHIND_MOVED
      }
    }
    return null
  }

  private fun itemViolation(kind: OpKind, item: PlannedItem, ownerDeviceId: String): PlanViolation? {
    val expectedFlows = when (kind) {
      OpKind.CREATE_FOLDER -> setOf(ItemFlow.CREATE_FOLDER)
      OpKind.RENAME -> setOf(ItemFlow.RENAME, ItemFlow.RENAME_CASE_ONLY)
      OpKind.MOVE -> setOf(ItemFlow.RENAME, ItemFlow.MOVE_ACROSS_SHARES)
      OpKind.TRASH -> setOf(ItemFlow.TRASH)
      OpKind.RESTORE -> setOf(ItemFlow.RESTORE)
      OpKind.PURGE_NOW -> setOf(ItemFlow.PURGE)
    }
    if (item.flow !in expectedFlows) return PlanViolation.WRONG_FLOW
    return when (item.flow) {
      ItemFlow.CREATE_FOLDER -> if (NasOpsPaths.isMediaPath(item.srcPath)) null else PlanViolation.APP_AREA_PATH

      ItemFlow.TRASH -> if (NasOpsPaths.isMediaPath(item.srcPath)) null else PlanViolation.APP_AREA_PATH

      ItemFlow.RENAME, ItemFlow.RENAME_CASE_ONLY, ItemFlow.MOVE_ACROSS_SHARES -> {
        val dst = item.dstPath
        when {
          dst == null || item.dstShare == null -> PlanViolation.MISSING_DESTINATION
          !NasOpsPaths.isMediaPath(item.srcPath) || !NasOpsPaths.isMediaPath(dst) -> PlanViolation.APP_AREA_PATH
          item.srcShare.equals(item.dstShare, ignoreCase = true) && item.srcPath == dst -> PlanViolation.SAME_LOCATION
          item.srcShare.equals(item.dstShare, ignoreCase = true) && item.isFolder && dst.isWithin(item.srcPath) -> PlanViolation.INTO_ITSELF
          else -> null
        }
      }

      ItemFlow.RESTORE -> {
        val dst = item.dstPath
        when {
          !NasOpsPaths.isTrashItem(item.srcPath) -> PlanViolation.NOT_A_TRASH_ITEM
          dst == null -> PlanViolation.MISSING_DESTINATION
          !NasOpsPaths.isMediaPath(dst) -> PlanViolation.APP_AREA_PATH
          item.trashId != item.srcPath.name -> PlanViolation.NOT_A_TRASH_ITEM
          else -> null
        }
      }

      ItemFlow.PURGE -> when {
        !NasOpsPaths.isTrashItem(item.srcPath) -> PlanViolation.NOT_A_TRASH_ITEM
        // Solo il cestino di questo dispositivo, e la voce con l'id del percorso: mai quello di un altro.
        item.srcPath.components[2] != ownerDeviceId || item.trashId != item.srcPath.name -> PlanViolation.NOT_OWN_TRASH
        else -> null
      }
    }
  }
}
