package dev.pampa.mycloudgallery.tools.release

import dev.pampa.mycloudgallery.core.release.codec.CodecException
import dev.pampa.mycloudgallery.core.release.codec.JArr
import dev.pampa.mycloudgallery.core.release.codec.JInt
import dev.pampa.mycloudgallery.core.release.codec.JObj
import dev.pampa.mycloudgallery.core.release.codec.JStr
import dev.pampa.mycloudgallery.core.release.codec.JValue
import dev.pampa.mycloudgallery.core.release.codec.ReleaseJson
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import java.time.Duration
import java.time.Instant

/**
 * `release/manifest.json`, il manifest **leggibile** che l'admin scrive a mano (docs/fase10-progetto.md §2.8 "Scrittura"): niente segreti, il
 * `seq` e le date li mette lo strumento. Piu' severo dell'app, per trovare gli errori prima di firmare: ogni campo sconosciuto, livello o
 * messaggio fuori dal catalogo e' un errore (l'app, per un campo nuovo di una versione futura, sceglierebbe il caso piu' severo).
 *
 * ```
 * {
 *   "app": "dev.pampa.mycloudgallery",
 *   "validDays": 90,
 *   "minVersionCode": 3,
 *   "blocks": [ { "from": 5, "to": 8, "level": "writes", "message": "security" } ],
 *   "models": [ { "id": "<id del catalogo>", "sha256": "<impronta del catalogo>", "urls": [ "https://huggingface.co/..." ] } ],
 *   "nota": "testo per chi legge il file: NON entra nel manifest firmato"
 * }
 * ```
 * Facoltativi: `minVersionCode`, `seq` (se c'e', deve essere maggiore dell'ultimo firmato), `nota`. `validDays` da 1 a 180 (di base 90).
 */
internal class HumanManifest(
  val seq: Long?,
  val validDays: Long,
  val minVersionCode: Int?,
  val blocks: List<VersionBlock>,
  val models: List<ModelHint>,
) {
  fun build(seq: Long, issued: Instant): ReleaseManifest =
    ReleaseManifest(ManifestLimits.SCHEMA, ManifestLimits.APP_ID, seq, issued, issued.plus(Duration.ofDays(validDays)), minVersionCode, blocks, models)

  companion object {
    private val KNOWN = setOf("app", "blocks", "minVersionCode", "models", "nota", "seq", "validDays")
    private val KNOWN_BLOCK = setOf("from", "level", "message", "to")
    private val KNOWN_MODEL = setOf("id", "sha256", "urls")

    /** Lancia `IllegalArgumentException` con un messaggio in italiano per ogni errore. */
    fun parse(text: String): HumanManifest {
      val root = try {
        ReleaseJson.parseLenient(text)
      } catch (e: CodecException) {
        throw IllegalArgumentException("manifest.json non e' JSON valido per lo strumento (${e.message}): niente null, niente decimali, niente chiavi ripetute.")
      } as? JObj ?: throw IllegalArgumentException("manifest.json deve essere un oggetto JSON.")
      unknown(root, KNOWN, "manifest")
      val app = string(root, "app")
      require(app == ManifestLimits.APP_ID) { "\"app\" deve essere ${ManifestLimits.APP_ID}." }
      val validDays = root.members["validDays"]?.let { long(it, "validDays") } ?: 90L
      require(validDays in 1..ManifestLimits.MAX_VALIDITY.toDays()) { "\"validDays\" va da 1 a ${ManifestLimits.MAX_VALIDITY.toDays()}." }
      val min = root.members["minVersionCode"]?.let { long(it, "minVersionCode") }
      require(min == null || min in 1..Int.MAX_VALUE.toLong()) { "\"minVersionCode\" deve essere un intero positivo." }
      val seq = root.members["seq"]?.let { long(it, "seq") }
      require(seq == null || seq >= 1) { "\"seq\" deve essere positivo." }
      val blocks = array(root, "blocks", ManifestLimits.MAX_BLOCKS).mapIndexed { i, value -> block(value, i) }
      val models = array(root, "models", ManifestLimits.MAX_MODELS).mapIndexed { i, value -> model(value, i) }
      return HumanManifest(seq, validDays, min?.toInt(), blocks, models)
    }

    private fun block(value: JValue, index: Int): VersionBlock {
      val obj = value as? JObj ?: throw IllegalArgumentException("blocks[$index] deve essere un oggetto.")
      unknown(obj, KNOWN_BLOCK, "blocks[$index]")
      val from = long(obj.members["from"], "blocks[$index].from")
      val to = long(obj.members["to"], "blocks[$index].to")
      require(from in 1..Int.MAX_VALUE.toLong() && to in from..Int.MAX_VALUE.toLong()) { "blocks[$index]: serve 1 <= from <= to." }
      val levelCode = string(obj, "level")
      val level = BlockLevel.entries.firstOrNull { it.code == levelCode }
        ?: throw IllegalArgumentException("blocks[$index].level: solo ${BlockLevel.entries.joinToString { it.code }}.")
      val messageCode = string(obj, "message")
      val message = ReleaseMessage.entries.firstOrNull { it.code == messageCode }
        ?: throw IllegalArgumentException("blocks[$index].message: solo il catalogo dell'app (${ReleaseMessage.entries.joinToString { it.code }}), mai testo libero.")
      return VersionBlock(from.toInt(), to.toInt(), level, message)
    }

    private fun model(value: JValue, index: Int): ModelHint {
      val obj = value as? JObj ?: throw IllegalArgumentException("models[$index] deve essere un oggetto.")
      unknown(obj, KNOWN_MODEL, "models[$index]")
      val urls = array(obj, "urls", ManifestLimits.MAX_URLS_PER_MODEL).map { (it as? JStr)?.value ?: throw IllegalArgumentException("models[$index].urls: solo testi.") }
      require(urls.isNotEmpty()) { "models[$index].urls: almeno un indirizzo." }
      for (url in urls) require(plausibleModelUrl(url)) { "models[$index]: \"$url\" non e' un indirizzo https di Hugging Face senza porta ne' parametri (l'app lo rifiuterebbe)." }
      return try {
        ModelHint(string(obj, "id"), string(obj, "sha256"), urls)
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("models[$index]: id o impronta non validi (l'id e l'impronta devono essere quelli del catalogo dell'app).")
      }
    }

    /** Un controllo anticipato (decide l'app con `PinnedUrl.of` e `ModelHosts`): https, host di Hugging Face, niente porta, utente o parametri. */
    fun plausibleModelUrl(url: String): Boolean {
      val m = Regex("^https://([a-z0-9.-]+)(/[A-Za-z0-9._~/%-]*)$").matchEntire(url) ?: return false
      val host = m.groupValues[1]
      return (host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co")) && "/../" !in url
    }

    private fun unknown(obj: JObj, known: Set<String>, where: String) {
      val extra = obj.members.keys - known
      require(extra.isEmpty()) { "$where: campi sconosciuti ${extra.sorted()} (ammessi: ${known.sorted()})." }
    }

    private fun string(obj: JObj, key: String): String = (obj.members[key] as? JStr)?.value ?: throw IllegalArgumentException("Manca il testo \"$key\".")

    private fun long(value: JValue?, name: String): Long = (value as? JInt)?.value ?: throw IllegalArgumentException("\"$name\" deve essere un intero.")

    private fun array(obj: JObj, key: String, max: Int): List<JValue> {
      val items = (obj.members[key] as? JArr)?.items ?: throw IllegalArgumentException("Manca l'elenco \"$key\" (anche vuoto: []).")
      require(items.size <= max) { "\"$key\": al piu' $max elementi." }
      return items
    }
  }
}
