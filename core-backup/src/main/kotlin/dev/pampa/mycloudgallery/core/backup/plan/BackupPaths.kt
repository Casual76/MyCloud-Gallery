package dev.pampa.mycloudgallery.core.backup.plan

import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

// LE DESTINAZIONI DEL BACKUP (Fase 5A, P4; docs/fase5-progetto.md D6, Q8, E-B17, E-N6, E-P6).
//
//     [<radice di prova>/]Backup/<cartella del dispositivo>/[<sorgente>/]AAAA/MM/<nome>
//
// - `AAAA/MM` sono anno e mese della **data di scatto nell'ora del luogo** (`local_day` del catalogo, gia' calcolato col fuso del
//   luogo): il fuso non si tocca qui.
// - La fotocamera sta direttamente sotto la cartella del dispositivo; gli screenshot in `Screenshot/` e ogni cartella aggiuntiva in
//   `<Nome>/` (Q8: dal PC le foto della fotocamera restano pulite). Una cartella di un volume diverso dal principale (la microSD, E-P6)
//   sta sotto `microSD/`, cosi' due schede o la scheda e la memoria interna non si mescolano.
// - Data assente, assurda (prima del 1990, o oltre domani) o impossibile (mese 13): `Senza data/` (E-B17), mai `1979/01`.
// - Il nome e' quello del file sul telefono, **validato con `SharePath`** (E-N6): un nome che `SharePath` rifiuta (caratteri vietati
//   da Windows, invisibili, nomi riservati, troppo lungo) non fa saltare il file, perche' i byte sono dell'utente: si ripulisce in modo
//   deterministico (lo stesso nome da' sempre lo stesso risultato, come chiede il diario D3). Il nome occupato lo scopre la
//   pubblicazione (E-B3: "nome (2)").
//
// Funzioni pure: nessuna lettura del NAS, nessun orologio. Per "oltre domani" l'ora e' quella del NAS (vedi [tomorrowOf]).

/** Le regole dei percorsi del backup. */
object BackupPaths {

  const val ROOT: String = "Backup"

  /** Data assente o assurda (E-B17). */
  const val UNDATED: String = "Senza data"

  /** Screenshot (Q8). */
  const val SCREENSHOTS: String = "Screenshot"

  /** Le cartelle di un volume diverso dal principale: la microSD (E-P6). */
  const val REMOVABLE: String = "microSD"

  /** Una cartella aggiuntiva senza nome ricavabile dal suo percorso. */
  const val OTHER: String = "Altro"

  /** Il nome di un file senza un nome utilizzabile. */
  const val FALLBACK_FILE: String = "File"

  /** Il volume principale del MediaStore. */
  const val PRIMARY_VOLUME: String = "external_primary"

  /** Prima di questo anno una data di scatto e' assurda (l'inventario ha file del 1979, E-B17). */
  const val FIRST_YEAR: Int = 1990

  /**
   * Senza l'ora del NAS non c'e' modo di sapere cos'e' "domani" (mai l'orologio del telefono, §4.3): resta una regola che non dipende
   * da nessun orologio, cioe' scartare solo le date che nessuna fotocamera puo' avere. Una data futura ma credibile finisce in una
   * cartella dell'anno sbagliato, che non toglie niente a nessuno; la data e' fissata nel diario al momento del piano e non cambia.
   */
  const val FALLBACK_LAST_YEAR: Int = 2100

  /** Il nome riservato alla cartella delle copie del guardiano (D9): non e' un dispositivo. */
  const val COMMON_AREA_FOLDER: String = "Area comune"

  /** La radice dei backup: `Backup`, oppure `<radice di prova>/Backup` con la recinzione di D13 (`mcg-campo/Backup`). */
  fun root(fenceRoot: SharePath? = null): SharePath = fenceRoot?.child(ROOT) ?: SharePath.of(ROOT)

  /**
   * La destinazione di un file: radice, cartella del dispositivo, sorgente, data e nome.
   *
   * @param latestDay l'ultimo giorno credibile (`aaaammgg`), da [tomorrowOf]; null = ora del NAS sconosciuta.
   */
  fun destination(
    folder: DeviceFolder,
    source: BackupSource,
    localDay: Int?,
    latestDay: Int?,
    fileName: String,
    fenceRoot: SharePath? = null,
  ): SharePath {
    var path = root(fenceRoot).child(folder.name)
    for (component in sourceFolders(source)) path = path.child(component)
    for (component in dateFolders(localDay, latestDay)) path = path.child(component)
    return path.child(fileName(fileName))
  }

  /**
   * La cartella del telefono di [source] come la scrive il MediaStore (`RELATIVE_PATH`, con la barra finale), per la recinzione di D13
   * (`BackupFence.allowsPhoneFolder`): la fotocamera e gli screenshot hanno qui la cartella canonica (`DCIM/Camera/`,
   * `Pictures/Screenshots/`). Sulla recinzione di prova (`DCIM/mcg-prova/`, `Pictures/mcg-prova/`) nessuna delle due passa.
   */
  fun phoneFolderOf(source: BackupSource): String =
    when (source) {
      BackupSource.Camera -> "DCIM/Camera/"
      BackupSource.Screenshots -> "Pictures/Screenshots/"
      is BackupSource.Folder -> source.relativePath
    }

  /** Le cartelle sotto quella del dispositivo per [source]: niente per la fotocamera (Q8). */
  fun sourceFolders(source: BackupSource): List<String> =
    when (source) {
      BackupSource.Camera -> emptyList()
      BackupSource.Screenshots -> listOf(SCREENSHOTS)
      is BackupSource.Folder -> {
        val name = component(source.relativePath.trim('/').substringAfterLast('/'), OTHER)
        if (source.volume == PRIMARY_VOLUME) listOf(name) else listOf(REMOVABLE, name)
      }
    }

  /** `AAAA/MM` della data di scatto, o `Senza data` (E-B17). */
  fun dateFolders(localDay: Int?, latestDay: Int?): List<String> {
    if (localDay == null || !isCredibleDay(localDay, latestDay)) return listOf(UNDATED)
    return listOf(String.format(Locale.ROOT, "%04d", localDay / 10_000), String.format(Locale.ROOT, "%02d", localDay / 100 % 100))
  }

  /**
   * Una data di scatto credibile: `aaaammgg` di un giorno che esiste, dal [FIRST_YEAR] a [latestDay] ("oltre domani" e' assurdo); senza
   * [latestDay] fino al [FALLBACK_LAST_YEAR].
   */
  fun isCredibleDay(localDay: Int, latestDay: Int?): Boolean {
    val year = localDay / 10_000
    val month = localDay / 100 % 100
    val day = localDay % 100
    if (localDay <= 0 || year < FIRST_YEAR || month !in 1..12) return false
    if (day !in 1..YearMonth.of(year, month).lengthOfMonth()) return false
    return if (latestDay != null) localDay <= latestDay else year <= FALLBACK_LAST_YEAR
  }

  /**
   * La regola dell'"oltre domani" (E-B17) in una funzione sola, per chi ha l'ora del NAS in millisecondi ([nasNowMs], null = non nota):
   * una data di scatto e' credibile se e' un giorno vero, dal 1990 al giorno UTC del NAS piu' uno ([tomorrowOf]); senza l'ora del NAS
   * valgono i soli limiti che non dipendono da nessun orologio (fino al [FALLBACK_LAST_YEAR]). La usano il piano e, con la stessa regola,
   * il guardiano delle copie private (`Backup/Area comune/AAAA/MM/`, D9).
   */
  fun isCredibleDayAt(localDay: Int, nasNowMs: Long?): Boolean = isCredibleDay(localDay, nasNowMs?.let(::tomorrowOf))

  /**
   * "Domani" come `aaaammgg`, dall'ora del NAS ([nasNowMs], millisecondi): il giorno UTC del NAS piu' uno. Una foto scattata a
   * UTC+14 mentre per il NAS e' ancora ieri sera ha `local_day` = domani rispetto al giorno UTC del NAS: e' credibile; il giorno dopo
   * ancora, no.
   */
  fun tomorrowOf(nasNowMs: Long): Int {
    val tomorrow = LocalDate.ofEpochDay(Math.floorDiv(nasNowMs, MILLIS_PER_DAY)).plusDays(1)
    return tomorrow.year * 10_000 + tomorrow.monthValue * 100 + tomorrow.dayOfMonth
  }

  // --- I nomi (E-N6) -------------------------------------------------------------------------------------------------

  /**
   * Il nome del file sul NAS: quello del telefono se `SharePath` lo accetta (accenti, emoji e nomi lunghi fino a 255 byte sono
   * ammessi), altrimenti ripulito: caratteri vietati da Windows, invisibili e "di confusione" (anche a larghezza piena) diventano `_`,
   * punti e spazi finali spariscono, un nome riservato di Windows (`CON.jpg`) prende un `_` davanti, un nome troppo lungo si accorcia
   * a un confine di carattere tenendo l'estensione. Mai vuoto: `File`.
   */
  fun fileName(raw: String): String = component(raw, FALLBACK_FILE)

  /** Un nome qualunque ripulito per diventare **un** componente valido di `SharePath` (o [fallback]). */
  fun component(raw: String, fallback: String): String {
    if (SharePath.isValidComponent(raw)) return raw
    var name = trimTail(replaceUnsafe(raw))
    if (name.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_COMPONENT_UTF8_BYTES) name = truncate(name)
    if (name.isNotEmpty() && !SharePath.isValidComponent(name)) name = "_$name"
    if (name.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_COMPONENT_UTF8_BYTES) name = truncate(name)
    if (name.isNotEmpty() && SharePath.isValidComponent(name)) return name
    return fallback
  }

  private const val MILLIS_PER_DAY: Long = 86_400_000L
  private const val WINDOWS_FORBIDDEN = "\\/:*?\"<>|"
  private const val MAX_EXTENSION_CHARS = 12

  /** Ogni carattere che `SharePath` rifiuterebbe (da solo o nella sua forma NFKC) diventa `_`, un punto di codice alla volta. */
  private fun replaceUnsafe(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
      val cp = raw.codePointAt(i)
      i += Character.charCount(cp)
      out.append(if (isSafe(cp)) String(Character.toChars(cp)) else "_")
    }
    return out.toString()
  }

  private fun isSafe(cp: Int): Boolean {
    if (cp in 0xD800..0xDFFF) return false // un surrogato spaiato (le coppie valide codePointAt le ha gia' unite)
    if (TextRules.isForbiddenCodePoint(cp)) return false
    val piece = String(Character.toChars(cp))
    if (piece.any { it in WINDOWS_FORBIDDEN }) return false
    val nfkc = Normalizer.normalize(piece, Normalizer.Form.NFKC)
    if (nfkc == piece) return true
    // Un carattere che in forma di compatibilita' diventa un separatore, un vietato o un invisibile (`／`, `：`, `＊`...).
    return nfkc.none { it in WINDOWS_FORBIDDEN || it == '/' } && !TextRules.hasForbidden(nfkc)
  }

  /** Toglie punti e spazi finali, anche quelli che lo diventano in forma NFKC (`．`, lo spazio ideografico). */
  private fun trimTail(name: String): String {
    var s = name
    while (s.isNotEmpty()) {
      val last = Normalizer.normalize(s, Normalizer.Form.NFKC).lastOrNull() ?: break
      if (last != '.' && last != ' ') break
      s = s.substring(0, s.offsetByCodePoints(s.length, -1))
    }
    return s
  }

  /** Accorcia a [TrustLimits.MAX_COMPONENT_UTF8_BYTES] byte a un confine di carattere, tenendo l'estensione. */
  private fun truncate(name: String): String {
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS) name.substring(dot) else ""
    var stem = name.substring(0, name.length - extension.length)
    val limit = TrustLimits.MAX_COMPONENT_UTF8_BYTES
    while (stem.isNotEmpty() && (stem + extension).toByteArray(Charsets.UTF_8).size > limit) {
      stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
    }
    return trimTail(stem) + extension
  }
}
