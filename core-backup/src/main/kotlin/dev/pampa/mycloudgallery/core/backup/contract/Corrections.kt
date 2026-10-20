package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 5B (docs/fase5-progetto.md §1.2 "Correzioni (5B)", §1.5 `CorrectionState`, §2.4 "Correzioni dall'app", D19, E-L8, E-E8, E-E9, L40;
// riga P0-B del §4.4): correggere DATA, LUOGO e ROTAZIONE di un media scrivendo la correzione **nel file**, come versione nuova, con l'originale
// conservato. Contratto, null-object e fake di P0-B; la sequenza vera (copia locale corretta con `MetadataWriter`, riletta e decodificata,
// `catalog.correct`, `PhoneRewriter.replace`, `VERSION` nel diario) e' di P8.
//
// **Regole che il contratto rende visibili** (le fa rispettare P8, le provano i suoi test):
//  - un media del telefono si corregge solo se e' **al sicuro** (l'originale e' sul NAS, riletto): altrimenti [CorrectionOutcome.NeedsSafeFirst];
//  - la riga del telefono si riscrive solo con il permesso del dialogo di sistema ([PhoneWriteGrant]) e dopo una [PhoneConfirmation];
//  - solo JPEG, PNG e WebP si scrivono nel file (Q6, L40): per gli altri formati la correzione resta nell'app e nell'indice
//    ([CorrectionReadiness.FORMAT_NOT_WRITABLE]) e il file non si tocca;
//  - un file rovinato dalla scrittura si scarta con l'originale intatto: il telefono torna com'era, o l'esito lo dice ([CorrectionFailure.PHONE_NOT_RESTORED]).

/** Che cosa l'utente chiede di correggere: i campi del modello (`CorrectionField`) con i valori nuovi. Mai nei log: data e luogo sono dell'utente. */
data class CorrectionRequest(val change: MetadataChange) {
  init {
    require(!change.isEmpty) { "Una correzione cambia almeno un campo." }
  }

  val fields: Set<CorrectionField> get() = change.fields

  override fun toString(): String = "CorrectionRequest(${fields.joinToString { it.code }})"
}

/** Da dove parte la correzione (D19): la copia del telefono o, per un media solo sul NAS, l'originale letto in cache. */
enum class CorrectionRoute(override val code: String) : StableCode {
  /** Il media sta sul telefono: copia locale corretta, poi riscrittura della riga con il dialogo di sistema, poi la versione sul NAS. */
  PHONE("phone"),

  /** Il media e' solo sul NAS: originale in cache con l'hash, copia corretta, verifica, versione al solito percorso. */
  NAS_ONLY("nas_only"),
}

/** Se la correzione si puo' fare adesso, o perche' no. */
enum class CorrectionReadiness(override val code: String) : StableCode {
  READY("ready"),

  /** Un media del telefono non al sicuro: prima il backup e la verifica (D19, `CorrectionState.NeedsSafeFirst`). */
  NEEDS_SAFE_FIRST("needs_safe_first"),

  /** HEIC, RAW, video (Q6, L40, E-E8): la correzione resta nell'app e nell'indice, il file non si tocca. */
  FORMAT_NOT_WRITABLE("format_not_writable"),

  /**
   * Area comune (D35, E-E9): il media proprio sta **solo** nell'area comune. **Scelta del P14: dalla 5B non si scrive mai nell'area comune**
   * (ne' una versione corretta, ne' un'archiviazione in `Versioni/` dell'area comune): la versione la vedrebbero e la cambierebbero tutti i
   * familiari, e un'archiviazione sposterebbe un file che altri vedono senza che lo sappiano (D2: il backup aggiunge solo nell'area privata).
   * La correzione si ferma qui, senza scrivere niente, e la UI lo dice ("nell'area comune la vedrebbero tutti"). Un foglio di conferma
   * ("La modifica sara' visibile a tutti i familiari") e' rimandato a dopo la chiusura della 5B (Q3), insieme alle scritture vere.
   */
  COMMON_AREA_NOTICE("common_area_notice"),

  /** Area in sola lettura: la correzione diventerebbe un media nuovo nell'area privata (D35, E-N9). */
  READ_ONLY_AREA("read_only_area"),

  /** Il NAS non risponde (E-B9). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Area congelata o scritture ferme (D32). */
  FROZEN("frozen"),

  NOT_MINE("not_mine"),

  /** Nessuna implementazione legata (null-object). */
  NOT_AVAILABLE("not_available"),
}

/**
 * Il piano di una correzione: il media, la versione di partenza, le righe del telefono da riscrivere (per il dialogo di sistema) e se si puo'
 * fare. E' il piano che la conferma firma: [hash] copre il media, la versione di partenza, i valori nuovi e la strada, quindi se nel frattempo
 * il file cambia (Samsung "Salva", il PC) la conferma non vale piu'.
 */
class CorrectionPlan(
  val mediaId: MediaId,
  val request: CorrectionRequest,
  /** La versione da cui parte la correzione (l'attuale), o null se il media non c'e' (o nessuna implementazione e' legata). */
  val baseVersion: VersionId?,
  val route: CorrectionRoute,
  val readiness: CorrectionReadiness,
  /** Le righe del telefono che la correzione riscrive: l'argomento di `PhoneMediaIntents.writeRequest`. Vuote per [CorrectionRoute.NAS_ONLY]. */
  val phoneRows: List<PhoneRef>,
) : ConfirmablePlan {

  override val requiredConfirmations: Int = 1

  override val hash: PhonePlanHash = PhonePlanHash.builder("correction")
    .string(mediaId.value)
    .string(baseVersion?.value)
    .string(route.code)
    .string(readiness.code)
    .long(request.change.takenAtUtcMillis)
    .long(request.change.offsetMinutes?.toLong())
    .string(
      when (val geo = request.change.geo) {
        GeoChange.Keep -> "keep"
        GeoChange.Clear -> "clear"
        is GeoChange.Set -> "set:${geo.point.latitude}:${geo.point.longitude}:${geo.point.altitudeMeters}"
      },
    )
    .int(request.change.orientation?.exif ?: 0)
    .list(phoneRows) { ref ->
      string(ref.volume)
      long(ref.mediaStoreId)
    }
    .build()

  override fun toString(): String = "CorrectionPlan(${route.code}, ${readiness.code}, ${request.fields.joinToString { it.code }})"
}

/** Perche' una correzione e' fallita (un guasto, non un limite): codici stabili. */
enum class CorrectionFailure(override val code: String) : StableCode {
  /** L'originale non si legge (telefono o cache): il media non cambia. */
  SOURCE_UNREADABLE("source_unreadable"),

  /** La copia corretta, riletta e decodificata, non e' valida: si scarta, l'originale e' intatto (D19). */
  RESULT_NOT_VALID("result_not_valid"),

  /** La riscrittura della riga del telefono non e' riuscita e la riga e' tornata com'era. */
  PHONE_WRITE_FAILED("phone_write_failed"),

  /** Il caso grave: la riga del telefono e' rimasta a meta' e non si e' potuta ripristinare; l'originale e' sul NAS e l'utente va avvisato. */
  PHONE_NOT_RESTORED("phone_not_restored"),

  /** Spazio del telefono finito, in cache o per la copia di sicurezza. */
  PHONE_FULL("phone_full"),

  /** Il NAS non ha accettato la versione nuova (rete, quota, spazio): la correzione e' nell'app e riprova con il backup. */
  NAS_FAILED("nas_failed"),
}

/** L'esito di [Corrections.apply]. Solo numeri e codici. */
sealed interface CorrectionOutcome {
  /** La correzione e' scritta nel file (se del telefono) e la versione nuova ([version]) e' nel diario: il backup la porta sul NAS. */
  data class Done(val version: VersionId) : CorrectionOutcome

  /** Serve il permesso di sistema per [rows] (`writeRequest`, `CorrectionState.AwaitingSystemDialog`): la UI lo chiede e richiama `apply` con il permesso. */
  data class NeedsWriteGrant(val rows: List<PhoneRef>) : CorrectionOutcome

  /** Il media del telefono non e' al sicuro: prima il backup e la verifica (D19). */
  data object NeedsSafeFirst : CorrectionOutcome

  /** Il formato non si scrive nel file (Q6, L40): la correzione resta nell'app e nell'indice. */
  data object FormatNotWritable : CorrectionOutcome

  /** La conferma non vale per il piano di adesso. */
  data class Rejected(val check: PhoneConfirmationCheck) : CorrectionOutcome

  /** Il piano non e' piu' quello (il file e' cambiato dopo il foglio): si ricalcola. */
  data object PlanChanged : CorrectionOutcome

  /** Il piano non permette la correzione ([CorrectionReadiness] diverso da `READY`): area comune senza foglio, sola lettura, NAS, congelamento. */
  data class Unavailable(val reason: CorrectionReadiness) : CorrectionOutcome

  data class Failed(val reason: CorrectionFailure) : CorrectionOutcome
}

/**
 * Le correzioni di data, luogo e rotazione (D19). Il risultato e' una **versione nuova** (`VersionKind.CORRECTED`, `VersionRules.stagesToRedo`:
 * solo META da rifare), mai una sostituzione: l'originale resta al sicuro sul NAS in `Versioni/` (D17).
 */
interface Corrections {

  /** Il piano di [change] su [mediaId], com'e' adesso. */
  suspend fun plan(mediaId: MediaId, change: CorrectionRequest): CorrectionPlan

  /**
   * Esegue [plan] (ricalcolato: se non e' piu' lo stesso, [CorrectionOutcome.PlanChanged]). [confirmation] si controlla contro il piano di
   * adesso. [writeGrant] e' il permesso del dialogo di sistema per le righe del telefono: senza, per un media del telefono, la risposta e'
   * [CorrectionOutcome.NeedsWriteGrant] e non si e' toccato niente.
   */
  suspend fun apply(plan: CorrectionPlan, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): CorrectionOutcome
}
