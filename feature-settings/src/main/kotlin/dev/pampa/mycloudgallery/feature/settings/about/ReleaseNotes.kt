package dev.pampa.mycloudgallery.feature.settings.about

/**
 * Le novita' di una versione (Fase 10, P12; docs/fase10-progetto.md D33): la sezione di `CHANGELOG.md` che sta dentro l'app come risorsa
 * (`res/raw/changelog.md`, una copia identica del file alla radice: un test le tiene uguali). Nessuna rete: il testo e' nell'APK.
 *
 * Una sezione comincia con `## <versione>`; sotto, fino a quattro titoli `### Novita'`, `### Correzioni`, `### Sicurezza`, `### Da sapere`,
 * e sotto ogni titolo frasi che cominciano con `- ` (una frase puo' andare a capo con righe rientrate). Tutto cio' che sta fuori da una
 * sezione (l'introduzione del file, che parla a chi prepara la versione) non entra mai nella pagina.
 */
internal class ReleaseNotesSection(val title: String, val items: List<String>) {
  override fun toString(): String = "ReleaseNotesSection(items=${items.size})"
}

/** Le novita' di [version] (come scritta nel file, per esempio `1.0.0`), nell'ordine del file. */
internal class ReleaseNotes(val version: String, val sections: List<ReleaseNotesSection>) {
  override fun toString(): String = "ReleaseNotes(sections=${sections.size})"
}

internal object ReleaseNotesParser {

  /** Tetti: il testo e' nostro, ma una pagina non deve mai crescere senza limite (un file rovinato o un errore di copia). */
  const val MAX_CHARS: Int = 65_536
  const val MAX_SECTIONS_PER_VERSION: Int = 8
  const val MAX_ITEMS_PER_SECTION: Int = 40
  const val MAX_ITEM_CHARS: Int = 600

  private val versionHeading = Regex("^##\\s+(\\S+)")
  private val sectionHeading = Regex("^###\\s+(.+?)\\s*$")
  private val itemStart = Regex("^-\\s+(.*)$")

  /** Tutte le versioni del testo, nell'ordine in cui compaiono (la piu' nuova per prima, per come si scrive il file). */
  fun parse(text: String): List<ReleaseNotes> {
    val lines = text.take(MAX_CHARS).replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val versions = ArrayList<ReleaseNotes>()
    var version: String? = null
    var sections = ArrayList<ReleaseNotesSection>()
    var title: String? = null
    var items = ArrayList<String>()
    var current: StringBuilder? = null

    fun closeItem() {
      current?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (items.size < MAX_ITEMS_PER_SECTION) items.add(it.take(MAX_ITEM_CHARS))
      }
      current = null
    }
    fun closeSection() {
      closeItem()
      val closed = title
      if (closed != null && items.isNotEmpty() && sections.size < MAX_SECTIONS_PER_VERSION) sections.add(ReleaseNotesSection(closed, items))
      title = null
      items = ArrayList()
    }
    fun closeVersion() {
      closeSection()
      val closed = version
      if (closed != null) versions.add(ReleaseNotes(closed, sections))
      version = null
      sections = ArrayList()
    }

    for (line in lines) {
      val heading = versionHeading.find(line)
      if (heading != null && !line.startsWith("###")) {
        closeVersion()
        version = heading.groupValues[1]
        continue
      }
      if (version == null) continue
      val section = sectionHeading.find(line)
      if (section != null) {
        closeSection()
        title = section.groupValues[1]
        continue
      }
      val item = itemStart.find(line)
      if (item != null && title != null) {
        closeItem()
        current = StringBuilder(item.groupValues[1])
        continue
      }
      // Una frase che va a capo: righe rientrate sotto un `- `. Una riga vuota chiude la frase.
      if (current != null && line.isNotBlank() && line.first().isWhitespace()) {
        current!!.append(' ').append(line.trim())
      } else if (line.isBlank()) {
        closeItem()
      }
    }
    closeVersion()
    return versions
  }

  /**
   * Le novita' della versione dell'app. [versionName] e' quella del pacchetto: la build di debug la porta con il suffisso `-debug`, che
   * qui si toglie. Se il testo non ha quella versione si dice con `null`: la pagina allora mostra la piu' recente e lo scrive.
   */
  fun forVersion(text: String, versionName: String?): ReleaseNotes? {
    val wanted = versionName?.removeSuffix("-debug")?.trim().orEmpty()
    return parse(text).firstOrNull { it.version == wanted }
  }
}
