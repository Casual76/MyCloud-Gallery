package dev.pampa.mycloudgallery.core.models.contract

import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import java.util.Locale

// Il CATALOGO DEI MODELLI fissato nell'app (docs/fase6-progetto.md §2.1, D16-D19). Valori di P0 (verificati il 21/10/2026: commit e
// date di Hugging Face invariati dal 6/10; dimensioni e SHA-256 dalla ricerca 0.3, ricontrollati sul file scaricato per il 270M e
// sull'asset di MagicTouch). Cambiano SOLO con un aggiornamento dell'app: nessun manifest remoto puo' far accettare un file diverso
// (D19; il manifest firmato della Fase 10 potra' solo AGGIUNGERE indirizzi per un id e un'impronta gia' elencati qui).

/** L'identita' stabile di un modello (minuscolo, cifre, `-` e `.`). Finisce nei nomi dei lavori e in Diagnostica come codice. */
@JvmInline
value class ModelId(val value: String) {
  init {
    require(ID.matches(value)) { "Id di modello non valido." }
  }

  override fun toString(): String = value

  private companion object {
    val ID = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")
  }
}

/** A cosa serve un modello. */
enum class ModelRole {
  /** EmbeddingGemma 2 270M solo testo, su CPU: il vettore delle query, su ogni dispositivo che cerca (D3, E-Q8). */
  QUERY_TEXT,

  /** EmbeddingGemma 2 440M testo e immagini, su GPU: il calcolo AI, solo sugli indicizzatori (D3). */
  INDEX_MULTIMODAL,

  /** MagicTouch: il ritaglio del soggetto nel viewer, dentro l'APK (D44, risposta Q11 (a)). */
  CUTOUT,
}

/** La licenza di un modello, da mostrare in Impostazioni → Informazioni e licenze. Nessun testo lungo qui: i file stanno negli asset. */
data class LicenseNote(
  /** Identificativo SPDX (es. `Apache-2.0`). */
  val spdx: String,
  /** Dove sta il testo ufficiale. */
  val url: String,
  /** Una nota breve (es. la Gemma Prohibited Use Policy), o null. */
  val note: String? = null,
)

/**
 * Gli host da cui l'app puo' scaricare un modello (D17, D33): Hugging Face e i suoi CDN, che cambiano nome nel tempo
 * (`cdn-lfs.hf.co`, `cdn-lfs-us-1.hf.co`, `cas-bridge.xethub.hf.co`, ...): per questo si ammettono **suffissi** e non nomi esatti.
 * Lo usano [PinnedUrl] e le redirezioni di `core-models/.../download/https/` (seguite a mano, al massimo [MAX_REDIRECTS], solo
 * `https` e solo verso host ammessi, E-S16). L'integrita' la da' l'impronta, non l'host.
 */
object ModelHosts {
  val EXACT: Set<String> = setOf("huggingface.co")
  val SUFFIXES: List<String> = listOf(".huggingface.co", ".hf.co")
  const val MAX_REDIRECTS: Int = 5

  /** Vero se [host] (nome DNS, non un indirizzo IP) e' ammesso. Maiuscole e punto finale non contano. */
  fun allowed(host: String): Boolean {
    val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
    if (h.isEmpty() || !HOST.matches(h)) return false
    return h in EXACT || SUFFIXES.any { h.endsWith(it) && h.length > it.length }
  }

  private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
}

/**
 * Un indirizzo HTTPS fissato nell'app per un modello: `https://<host ammesso>/<percorso>`, **senza** porta, utente, parametri ne'
 * frammento (nessun identificativo verso l'host, D17). Si costruisce solo con [of], che rifiuta tutto il resto.
 */
class PinnedUrl private constructor(val value: String, val host: String) {
  override fun equals(other: Any?): Boolean = other is PinnedUrl && other.value == value

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = value

  companion object {
    private val URL = Regex("^https://([A-Za-z0-9.-]+)(/[A-Za-z0-9._~/%-]*)$")

    fun of(url: String): PinnedUrl? {
      val m = URL.matchEntire(url) ?: return null
      val host = m.groupValues[1].lowercase(Locale.ROOT)
      if (!ModelHosts.allowed(host)) return null
      if ("/../" in url || url.endsWith("/..")) return null
      return PinnedUrl(url, host)
    }
  }
}

/** Un modello fissato nell'app (D16): cosa e', quanto pesa, la sua impronta, da dove si prende. */
data class ModelSpec(
  val id: ModelId,
  val role: ModelRole,
  /** Lo spazio dei vettori che produce; null per il ritaglio. */
  val space: EmbeddingSpace?,
  val fileName: String,
  val sizeBytes: Long,
  val sha256: Sha256,
  val license: LicenseNote,
  /** Gli indirizzi HTTPS, in ordine (vuoto per un modello dentro l'APK). */
  val https: List<PinnedUrl>,
  /** Il percorso nell'APK (asset) per un modello che viaggia con l'app (Q11 (a)); null per quelli da scaricare. */
  val bundledAsset: String? = null,
) {
  init {
    require(sizeBytes > 0) { "Dimensione non valida." }
    require(FILE.matches(fileName)) { "Nome di file non valido." }
    require((bundledAsset != null) xor https.isNotEmpty()) { "Un modello o e' nell'APK o si scarica, mai tutte e due ne' nessuna." }
  }

  override fun toString(): String = "ModelSpec($id, $role, $sizeBytes B)"

  private companion object {
    val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
  }
}

/** Il catalogo (§2.1). */
object ModelCatalog {
  private val APACHE = LicenseNote("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")
  private val GEMMA = APACHE.copy(note = "Gemma Prohibited Use Policy (scheda del modello di litert-community)")

  /** Ricerca 270M (query su CPU, ogni dispositivo e ogni profilo del tablet che cerca). */
  val QUERY_270M: ModelSpec = ModelSpec(
    id = ModelId("embeddinggemma2-270m-text"),
    role = ModelRole.QUERY_TEXT,
    space = SpaceCode.EMBEDDINGGEMMA2_FLOAT_V1.space,
    fileName = "embeddinggemma-2-text-270m.litertlm",
    sizeBytes = 164_626_432L,
    sha256 = Sha256("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"),
    license = GEMMA,
    https = listOfNotNull(
      PinnedUrl.of(
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/" +
          "9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm",
      ),
    ),
  )

  /** Indicizzatore 440M (calcolo AI su GPU, solo gli indicizzatori). */
  val INDEX_440M: ModelSpec = ModelSpec(
    id = ModelId("embeddinggemma2-440m-text-vision"),
    role = ModelRole.INDEX_MULTIMODAL,
    space = SpaceCode.EMBEDDINGGEMMA2_FLOAT_V1.space,
    fileName = "embeddinggemma-2-text-vision-440m.litertlm",
    sizeBytes = 387_710_976L,
    sha256 = Sha256("92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"),
    license = GEMMA,
    https = listOfNotNull(
      PinnedUrl.of(
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/" +
          "e301f74d5551b0c2641bd5cb4652a76239d5c5f8/embeddinggemma-2-text-vision-440m.litertlm",
      ),
    ),
  )

  /**
   * MagicTouch versione 1 (float32, ingresso 512x512), dentro l'APK come asset di core-ai (risposta Q11 (a)), con la licenza e gli
   * avvisi accanto (`core-ai/src/main/assets/cutout/`). Scaricato il 21/10/2026 dal sito dei modelli di MediaPipe
   * (`storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite`, MD5 uguale a quello
   * dichiarato dal server). La versione 2 (int8 768x768) e' un pacchetto `.task` da 30,5 MB, non approvato: P13 sceglie con le misure
   * e, se volesse la 2, serve un nuovo consenso allo scaricamento (docs/decisioni.md).
   */
  val CUTOUT_MAGICTOUCH: ModelSpec = ModelSpec(
    id = ModelId("magictouch-v1-float32"),
    role = ModelRole.CUTOUT,
    space = null,
    fileName = "magic_touch.tflite",
    sizeBytes = 6_227_884L,
    sha256 = Sha256("e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25"),
    license = APACHE.copy(note = "Scheda del modello MagicTouch di Google (13/03/2023)"),
    https = emptyList(),
    bundledAsset = "cutout/magic_touch.tflite",
  )

  val ALL: List<ModelSpec> = listOf(QUERY_270M, INDEX_440M, CUTOUT_MAGICTOUCH)

  fun byId(id: ModelId): ModelSpec? = ALL.firstOrNull { it.id == id }

  /**
   * I modelli che servono a un dispositivo con questi [roles] (D3, D17): il 270M e il ritaglio sempre, il 440M solo agli
   * indicizzatori. L'ordine e' quello in cui conviene averli.
   */
  fun needed(roles: Set<DeviceRole>): List<ModelSpec> =
    if (DeviceRole.INDEXER in roles) listOf(QUERY_270M, CUTOUT_MAGICTOUCH, INDEX_440M) else listOf(QUERY_270M, CUTOUT_MAGICTOUCH)
}
