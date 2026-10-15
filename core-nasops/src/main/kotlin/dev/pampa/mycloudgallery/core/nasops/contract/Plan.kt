package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

// Fase 4B (docs/fase4-progetto.md §2.7 "Regole comuni", D24): il piano di un'operazione, cio' che il foglio di conferma mostra
// all'utente ("Spostare 312 elementi, 4,1 GB, in Famiglia/Viaggi?") e a cui la conferma si lega con un'impronta. Tipi di P0-B; la
// pianificazione (controlli, spazio, nomi) e' di P12.

/** L'identita' di un'operazione nel diario: un id da 128 bit in base32 (`TrustIds`). Mai nei log. */
@JvmInline
value class OpId(val value: String) {
  init {
    require(TrustIds.isValid(value)) { "Un id di operazione sono 26 caratteri base32." }
  }

  override fun toString(): String = "OpId"
}

/**
 * L'impronta di un piano (SHA-256 di una codifica canonica di cio' che l'operazione fara'): la conferma dell'utente vale per
 * **questo** piano e non per un altro. Se il piano cambia (un elemento in piu', un'altra destinazione), l'impronta cambia e la
 * conferma non vale piu'.
 */
@JvmInline
value class PlanHash(val hex: String) {
  init {
    require(HEX_64.matches(hex)) { "Un'impronta di piano sono 64 cifre esadecimali minuscole." }
  }

  override fun toString(): String = "PlanHash(${hex.take(8)}…)"

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * L'impronta del piano di [kind] con questi [items], nell'ordine dato, e con gli esclusi ([skipped]: cio' che il piano lascia dov'e').
     * Deterministica: stessa lista, stessa impronta.
     *
     * R11: gli esclusi entrano nell'impronta. Una conferma data per "lascia i nascosti dove sono" porta l'impronta di QUEL piano, con la sua
     * lista di esclusi: non puo' avviare "sposta tutto" (altri elementi, nessun escluso), ne' un piano con altri esclusi. Senza esclusi la
     * codifica e' quella di sempre.
     */
    fun of(kind: OpKind, items: List<PlannedItem>, skipped: List<SkippedItem> = emptyList()): PlanHash {
      val bytes = ByteArrayOutputStream()
      DataOutputStream(bytes).use { out ->
        out.writeUTF("mcg-op-plan")
        out.writeUTF(kind.code)
        out.writeInt(items.size)
        for (item in items) {
          out.writeInt(item.index)
          out.writeUTF(item.flow.code)
          out.writeUTF(item.srcShare)
          out.writeUTF(item.srcPath.value)
          out.writeUTF(item.dstShare.orEmpty())
          out.writeUTF(item.dstPath?.value.orEmpty())
          out.writeBoolean(item.isFolder)
          out.writeLong(item.size)
          out.writeBoolean(item.stamp != null)
          item.stamp?.let {
            out.writeLong(it.size)
            out.writeLong(it.modifiedAtMillis)
            out.writeLong(it.fileId ?: -1L)
          }
          out.writeUTF(item.trashId.orEmpty())
          // R2: per una cartella eliminata, quanti file contiene (contati alla pianificazione): la conferma vale per QUEI numeri.
          out.writeInt(item.contentFiles ?: -1)
        }
        if (skipped.isNotEmpty()) {
          out.writeUTF("skipped")
          out.writeInt(skipped.size)
          for (entry in skipped) {
            out.writeUTF(entry.share)
            out.writeUTF(entry.path.value)
            out.writeUTF(entry.reason.code)
          }
        }
      }
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
      return PlanHash(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
  }
}

/**
 * Un elemento del piano: cosa si fa e su cosa. [dstShare] e [dstPath] sono il posto di arrivo **iniziale**: se il nome e' occupato
 * l'esecuzione sceglie un nome alternativo e lo scrive nel diario (§2.7), il piano non cambia.
 *
 * @property srcPath il file o la cartella di partenza. Per un ripristino o "elimina subito" e' il file nel cestino
 *   (`trash/<proprietario>/files/<id>`), derivato dal proprietario e dall'id, mai da un percorso letto dal NAS.
 * @property stamp il timbro atteso di un file (E-D13); null per cartelle, ripristini e creazioni.
 * @property trashId l'id della voce del cestino (ripristino, elimina subito).
 * @property contentFiles per una cartella da eliminare (R2 della revisione 4B): i file che contiene, contati scendendo in tutte le
 *   sottocartelle; [size] e' allora la somma dei loro byte. Entrambi entrano nell'impronta del piano.
 */
data class PlannedItem(
  val index: Int,
  val flow: ItemFlow,
  val srcShare: String,
  val srcPath: SharePath,
  val dstShare: String?,
  val dstPath: SharePath?,
  val isFolder: Boolean,
  val size: Long,
  val stamp: NasStamp?,
  val trashId: String? = null,
  val contentFiles: Int? = null,
) {
  init {
    require(index >= 0 && size >= 0) { "Indice o dimensione negativi." }
    require(trashId == null || TrustIds.isValid(trashId)) { "Id di cestino non valido." }
    require(contentFiles == null || (isFolder && contentFiles >= 0)) { "Il conteggio del contenuto e' solo delle cartelle." }
  }

  override fun toString(): String = "PlannedItem(#$index, ${flow.code})"
}

/** Un elemento che l'operazione lascia fuori, con il perche' (E-D11: "agisce solo dove si puo' e mostra l'elenco del resto"). */
data class SkippedItem(val share: String, val path: SharePath, val reason: SkipReason) {
  override fun toString(): String = "SkippedItem(${reason.code})"
}

enum class SkipReason(override val code: String) : StableCode {
  /** La share e' in sola lettura per questo account (E-N9). */
  READ_ONLY("read_only"),
  SAME_LOCATION("same_location"),

  /** Sarebbe una cartella spostata dentro se stessa. */
  INTO_ITSELF("into_itself"),
  SOURCE_MISSING("source_missing"),

  /** R11: un file che l'utente ha chiesto di lasciare dov'e' (un media nascosto, spostando nell'area comune). */
  LEFT_BEHIND("left_behind"),
}

/** Gli avvisi del foglio di conferma. */
enum class PlanWarning(override val code: String) : StableCode {
  /** Tra share diverse: copia lato server e verifica completa, poi la sorgente nel cestino (piu' lento, richiede spazio). */
  CROSS_SHARE_COPY("cross_share_copy"),

  /** "Elimina subito": definitivo; il foglio ricorda che il NAS potrebbe non avere un backup esterno (Q8). */
  PERMANENT_DELETE("permanent_delete"),
  FOLDER_INCLUDED("folder_included"),
  SOME_SKIPPED("some_skipped"),
  NAME_ADJUSTED("name_adjusted"),

  /** R2: l'eliminazione porta nel cestino molti file (oltre [PlanSummary.MANY_FILES]): il foglio lo dice in evidenza. */
  MANY_FILES("many_files"),
}

/**
 * Il riepilogo del foglio di conferma. [destShare] e [destFolder] sono i nomi scelti dall'utente (mai nei log).
 *
 * @property fileCount i file che l'operazione tocca davvero: quelli scelti e (R2) quelli dentro le cartelle da eliminare, contati.
 * @property totalBytes i loro byte (per una cartella da eliminare, la somma del contenuto).
 */
data class PlanSummary(
  val kind: OpKind,
  val itemCount: Int,
  val folderCount: Int,
  val totalBytes: Long,
  val destShare: String?,
  val destFolder: SharePath?,
  val fileCount: Int = itemCount - folderCount,
) {
  override fun toString(): String = "PlanSummary(${kind.code}, $itemCount elementi, $fileCount file, $totalBytes byte)"

  companion object {
    /** R2: oltre questi file il foglio di un'eliminazione lo dice in evidenza ([PlanWarning.MANY_FILES]). */
    const val MANY_FILES: Int = 500
  }
}

/**
 * Il piano di un'operazione, calcolato da `NasOperations.plan` (P12) senza modificare niente. E' cio' che l'utente vede e conferma:
 * l'esecuzione parte solo con la [UserConfirmation] che ne porta l'[hash].
 *
 * [requiredConfirmations]: 1 per tutto; 2 per "Elimina subito" (doppia conferma, Q8).
 */
class OpPlan(
  val kind: OpKind,
  val items: List<PlannedItem>,
  val skipped: List<SkippedItem>,
  val summary: PlanSummary,
  val warnings: Set<PlanWarning>,
) {
  val hash: PlanHash = PlanHash.of(kind, items, skipped)

  /** R11: i file che il piano lascia dov'erano per scelta dell'utente (i nascosti, spostando nell'area comune). */
  val leftBehind: List<SkippedItem> get() = skipped.filter { it.reason == SkipReason.LEFT_BEHIND }

  val requiredConfirmations: Int get() = if (kind.permanent) 2 else 1

  override fun toString(): String = "OpPlan(${kind.code}, ${items.size} elementi, ${skipped.size} esclusi)"
}

/** Perche' una richiesta non diventa un piano. */
enum class PlanRejection(override val code: String) : StableCode {
  EMPTY("empty"),

  /** La share (o la destinazione) e' in sola lettura per questo account (E-N9). */
  READ_ONLY("read_only"),

  /** Il nome non e' valido; il foglio propone la correzione (E-D9). */
  INVALID_NAME("invalid_name"),
  NAME_EXISTS("name_exists"),
  SAME_LOCATION("same_location"),
  INTO_ITSELF("into_itself"),
  SOURCE_MISSING("source_missing"),

  /** Un percorso e' dentro `.mycloudgallery/` o fuori dalla share: mai un'operazione sui file dell'app. */
  PATH_NOT_ALLOWED("path_not_allowed"),
  TOO_MANY_ITEMS("too_many_items"),

  /** Spazio sulla destinazione minore della dimensione piu' 1 GiB (E-D5): niente spostamenti a meta'. */
  NOT_ENOUGH_SPACE("not_enough_space"),

  /** La share e' congelata (D10) o le scritture sono ferme (emergenza): nessuna operazione nuova. */
  WRITES_BLOCKED("writes_blocked"),
  NAS_UNREACHABLE("nas_unreachable"),

  /** Una voce del cestino da eliminare subito non e' nel diario locale: non e' mia, non si elimina. */
  NOT_MINE("not_mine"),
}
