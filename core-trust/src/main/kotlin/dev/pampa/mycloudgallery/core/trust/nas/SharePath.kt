package dev.pampa.mycloudgallery.core.trust.nas

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.violation
import java.text.Normalizer

/**
 * Un percorso RELATIVO alla radice di una share, gia' validato: l'unico modo in cui un percorso letto
 * dal NAS puo' arrivare a un'operazione sui file (`docs/formato-nas.md` §8).
 *
 * Forma: componenti separati da `/`, mai `\`; niente radice, niente unita' (`C:`), niente UNC
 * (`\\server\x`), niente `.`/`..`, niente componenti vuoti. Ogni componente: al massimo 255 byte UTF-8,
 * nessun carattere vietato da [TextRules], nessun carattere vietato da Windows (`\ / : * ? " < > |`),
 * nessun punto o spazio finale, nessun nome riservato di Windows (`CON`, `NUL`, `COM1`, `LPT¹`,
 * `CONIN$`... anche con estensione), e lo stesso vale per la sua forma NFKC: `．．` o `／` (caratteri
 * "a larghezza piena" che qualche livello potrebbe trasformare in `..` e `/`) sono rifiutati.
 *
 * Il percorso NON viene normalizzato in silenzio: o e' gia' nella forma sicura o e' rifiutato, cosi' due
 * dispositivi non possono interpretarlo in due modi.
 */
class SharePath private constructor(val components: List<String>) {

  /** La forma testuale, con `/`. */
  val value: String = components.joinToString("/")

  val name: String get() = components.last()

  fun child(component: String): SharePath = SharePath(components + checkComponent(component))

  fun parent(): SharePath? = if (components.size <= 1) null else SharePath(components.dropLast(1))

  /** Vero se questo percorso e' [prefix] o sta sotto [prefix]. */
  fun isWithin(prefix: SharePath): Boolean =
    components.size >= prefix.components.size && components.subList(0, prefix.components.size) == prefix.components

  override fun equals(other: Any?): Boolean = other is SharePath && other.components == components

  override fun hashCode(): Int = components.hashCode()

  override fun toString(): String = "SharePath(${components.size} componenti)"

  companion object {

    /**
     * Valida un percorso letto dal NAS. Lancia una violazione [RejectReason.HOSTILE_PATH] (da convertire
     * in rifiuto al confine dell'API).
     */
    fun parse(raw: String): SharePath {
      if (raw.isEmpty()) hostile("percorso vuoto")
      if (raw[0] == '/' || raw[0] == '\\') hostile("percorso assoluto o UNC")
      if (raw.indexOf('\\') >= 0) hostile("separatore '\\' non ammesso")
      if (raw.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_PATH_UTF8_BYTES) hostile("percorso troppo lungo")
      val parts = raw.split('/')
      if (parts.size > TrustLimits.MAX_PATH_COMPONENTS) hostile("troppi livelli")
      return SharePath(parts.map(::checkComponent))
    }

    /**
     * Valida il percorso di un MEDIA (cestino, album, voci dell'indice): come [parse], e in piu' non puo'
     * puntare dentro la cartella dell'app (`.mycloudgallery`, in qualunque combinazione di maiuscole).
     */
    fun parseMediaPath(raw: String): SharePath {
      val path = parse(raw)
      if (path.components[0].equals(TrustFormat.ROOT_DIR, ignoreCase = true)) hostile("percorso dentro la cartella dell'app")
      return path
    }

    /** Costruisce un percorso da componenti scritti dall'app (stessi controlli). */
    fun of(vararg components: String): SharePath {
      require(components.isNotEmpty())
      return SharePath(components.map(::checkComponent))
    }

    /** Vero se [component] e' un componente valido. */
    fun isValidComponent(component: String): Boolean =
      try {
        checkComponent(component)
        true
      } catch (e: Exception) {
        false
      }

    internal fun checkComponent(component: String): String {
      if (component.isEmpty()) hostile("componente vuoto")
      if (component == "." || component == "..") hostile("componente '.' o '..'")
      if (component.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_COMPONENT_UTF8_BYTES) hostile("componente troppo lungo")
      if (TextRules.hasForbidden(component)) hostile("caratteri di controllo o invisibili")
      checkWindowsRules(component)
      // Forma di compatibilita': i caratteri "a larghezza piena" e simili non devono diventare
      // separatori, punti o nomi riservati in nessun livello che normalizzi.
      val nfkc = Normalizer.normalize(component, Normalizer.Form.NFKC)
      if (nfkc != component) {
        if (nfkc.isEmpty() || nfkc == "." || nfkc == ".." || nfkc.contains('/')) hostile("caratteri di confusione")
        if (TextRules.hasForbidden(nfkc)) hostile("caratteri di confusione")
        checkWindowsRules(nfkc)
      }
      return component
    }

    private fun checkWindowsRules(component: String) {
      for (c in component) {
        if (c in WINDOWS_FORBIDDEN) hostile("carattere vietato da Windows")
      }
      val last = component.last()
      if (last == '.' || last == ' ') hostile("punto o spazio finale")
      val base = component.substringBefore('.').trimEnd(' ').uppercase(java.util.Locale.ROOT)
      if (base in RESERVED_NAMES) hostile("nome riservato di Windows")
    }

    private const val WINDOWS_FORBIDDEN = "\\/:*?\"<>|"

    private val RESERVED_NAMES: Set<String> = buildSet {
      addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$"))
      for (suffix in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
        add("COM$suffix")
        add("LPT$suffix")
      }
    }

    private fun hostile(message: String): Nothing = violation(RejectReason.HOSTILE_PATH, message)
  }
}
