package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.metadata.format.NameParts
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaTrait
import java.util.Locale

// Sorgente, tratti e gruppi (docs/fase3-progetto.md §2.2, tabelle "Classificazione" e "Gruppi").

/** L'esito della classificazione di un file: da dove viene e quali tratti ha gia' il suo percorso o il suo nome. */
data class Classified(
  val source: MediaSource,
  val traits: Set<MediaTrait>,
)

/**
 * Da dove viene un media: screenshot, WhatsApp (anche Business, adesivi esclusi), Quick Share, fotocamera, altro.
 * Si decide da volume, percorso relativo, nome, pacchetto proprietario (`OWNER_PACKAGE_NAME`) e tipo MIME. Il
 * tratto `SCREEN_RECORDING` viene dai video in `DCIM/Screen recordings/` o chiamati `Screen_Recording_*`.
 *
 * Le regole, in ordine di priorita' (la prima che si applica vince; maiuscole e minuscole non contano):
 * 1. `SCREENSHOT`: percorso `DCIM/Screenshots/` o `Pictures/Screenshots/`, nome `Screenshot_*`; le registrazioni dello schermo
 *    (`DCIM/Screen recordings/`, `Movies/Screen recordings/`, nome `Screen_Recording*`) sono `SCREENSHOT` con il tratto `SCREEN_RECORDING`;
 * 2. `WHATSAPP`: `Android/media/com.whatsapp/.../WhatsApp Images|Video|Animated Gifs/` (anche `Sent/`), lo stesso per WhatsApp
 *    Business (`com.whatsapp.w4b`, `WhatsApp Business ...`), il percorso storico `WhatsApp/Media/...`, il pacchetto proprietario,
 *    il nome `IMG-aaaammgg-WA nnnn` / `VID-...`. Gli adesivi (`WhatsApp Stickers`, `STK-...`) e le foto profilo restano `OTHER`;
 * 3. `QUICK_SHARE`: `Download/Quick Share/`, `Download/Nearby Share/`;
 * 4. `CAMERA`: `DCIM/Camera/`;
 * 5. `OTHER`: il resto.
 *
 * Il volume non conta: una microSD con la stessa struttura di cartelle classifica allo stesso modo.
 */
object SourceClassifier {

  private val WHATSAPP_OWNERS = setOf("com.whatsapp", "com.whatsapp.w4b")
  private val WHATSAPP_MEDIA_FOLDERS = listOf("whatsapp images", "whatsapp video", "whatsapp animated gifs",
    "whatsapp business images", "whatsapp business video", "whatsapp business animated gifs")
  private val WHATSAPP_NAME = Regex("""^(?:img|vid)-\d{8}-wa\d+""")
  private val SCREEN_RECORDING_FOLDERS = listOf("dcim/screen recordings/", "movies/screen recordings/", "pictures/screen recordings/", "dcim/screenrecorder/", "movies/screenrecorder/")
  private val SCREENSHOT_FOLDERS = listOf("dcim/screenshots/", "pictures/screenshots/")
  private val QUICK_SHARE_FOLDERS = listOf("download/quick share/", "download/nearby share/")

  @Suppress("UNUSED_PARAMETER")
  fun classify(volume: String, relativePath: String, displayName: String, ownerPackage: String?, mime: String?): Classified {
    val path = normalize(relativePath)
    val name = displayName.lowercase(Locale.ROOT)
    val isVideo = mime?.startsWith("video/", ignoreCase = true) == true ||
      (mime == null && name.substringAfterLast('.', "") in VIDEO_EXTENSIONS)
    val isImage = mime?.startsWith("image/", ignoreCase = true) == true

    val recordingByPath = SCREEN_RECORDING_FOLDERS.any { path.startsWith(it) }
    val recordingByName = name.startsWith("screen_recording") || name.startsWith("screenrecord") || name.startsWith("screen recording")
    if ((recordingByPath || recordingByName) && !isImage) {
      val traits = if (isVideo || mime == null) setOf(MediaTrait.SCREEN_RECORDING) else emptySet()
      return Classified(MediaSource.SCREENSHOT, traits)
    }
    if (SCREENSHOT_FOLDERS.any { path.startsWith(it) } || name.startsWith("screenshot")) {
      return Classified(MediaSource.SCREENSHOT, emptySet())
    }

    val sticker = path.contains("stickers") || name.startsWith("stk-")
    val profile = path.contains("profile photos")
    if (!sticker && !profile) {
      val ownedByWhatsApp = ownerPackage != null && ownerPackage in WHATSAPP_OWNERS
      val inWhatsAppFolder = (path.startsWith("android/media/com.whatsapp/") || path.startsWith("android/media/com.whatsapp.w4b/") ||
        path.startsWith("whatsapp/media/")) && WHATSAPP_MEDIA_FOLDERS.any { path.contains("$it/") }
      if (ownedByWhatsApp || inWhatsAppFolder || WHATSAPP_NAME.containsMatchIn(name)) {
        return Classified(MediaSource.WHATSAPP, emptySet())
      }
    }

    if (QUICK_SHARE_FOLDERS.any { path.startsWith(it) }) return Classified(MediaSource.QUICK_SHARE, emptySet())
    if (path.startsWith("dcim/camera/")) return Classified(MediaSource.CAMERA, emptySet())
    return Classified(MediaSource.OTHER, emptySet())
  }

  /**
   * La sorgente che possiede la **cartella** [relativePath], qualunque sia il nome del file (O-2 delle prove sul campo): `CAMERA` per
   * `DCIM/Camera/`, `SCREENSHOT` per le cartelle degli screenshot e delle registrazioni dello schermo, null per tutte le altre. Le cartelle
   * possedute hanno il loro interruttore nel backup ("Fotocamera", "Screenshot"); le altre l'utente le sceglie una per una, e un file che sta
   * li' si salva con la sua cartella anche se si chiama "Screenshot_...".
   */
  fun folderOwner(relativePath: String): MediaSource? {
    val path = normalize(relativePath)
    return when {
      SCREENSHOT_FOLDERS.any { path.startsWith(it) } || SCREEN_RECORDING_FOLDERS.any { path.startsWith(it) } -> MediaSource.SCREENSHOT
      path.startsWith("dcim/camera/") -> MediaSource.CAMERA
      else -> null
    }
  }

  private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "3g2", "mkv", "webm", "avi")

  /** Minuscolo, con `/`, senza `/` iniziale e con `/` finale: `DCIM\Camera` e `/DCIM/Camera/` danno `dcim/camera/`. */
  private fun normalize(relativePath: String): String {
    val p = relativePath.replace('\\', '/').trim().trimStart('/').lowercase(Locale.ROOT)
    return if (p.isEmpty() || p.endsWith("/")) p else "$p/"
  }
}

/**
 * Un file che potrebbe far parte di un gruppo. Tutto cio' che serve per decidere senza rileggere il file: il
 * catalogo e l'estrazione lo forniscono.
 */
data class GroupCandidate(
  val id: MediaId,
  /** La chiave della cartella (`Location.folderKey`): un gruppo sta in una cartella sola. */
  val folderKey: String,
  val displayName: String,
  val mime: String?,
  val kind: MediaKind,
  val takenAtMs: Long,
  val hint: GroupHint?,
) {
  // Il nome del file e' un dato personale.
  override fun toString(): String = "GroupCandidate($kind)"
}

/** Un gruppo proposto: i membri e la copertina (il JPEG di una coppia, `_COVER` o il primo di una raffica). */
data class ProposedGroup(
  val kind: MediaGroupKind,
  val members: List<MediaId>,
  val cover: MediaId,
) {
  init {
    require(members.size >= 2) { "Un gruppo ha almeno due membri." }
    require(cover in members) { "La copertina e' un membro del gruppo." }
  }
}

/**
 * Raffiche e coppie RAW+JPEG. **Nel dubbio non si raggruppa**: un gruppo sbagliato nasconde una foto, un gruppo
 * mancato costa solo una cella in piu' (D20). Un media sta in un gruppo solo.
 *
 * **RAW+JPEG.** Stessa cartella, stesso nome (senza estensione), esattamente un `.dng` e un'immagine `.jpg`/`.jpeg`/`.heic`/`.heif`,
 * scattati a meno di 2 s l'uno dall'altro: copertina l'immagine. Un terzo file dello stesso nome base fra i candidati
 * (due JPEG per un DNG) rende la coppia ambigua e non la forma.
 *
 * **Raffica.** Almeno 2 foto (non RAW, non video) di una cartella con lo stesso nome base e un suffisso di raffica
 * (`_BURSTnnn`, `_nnn`, `(n)`: vedi `NameParts`), con numeri di scatto diversi, **tutte dello stesso modello di
 * fotocamera** (il [GroupHint.cameraModel] deve esserci ed essere uguale) e scatti consecutivi a meno di 1 s l'uno dall'altro,
 * in ordine di numero. Con `(n)` gli scatti devono avere ore diverse (due copie dello stesso file non sono una raffica).
 * Se i numeri sono interrotti da un buco di tempo la raffica si spezza in due e le parti da una foto sola restano
 * singole. Copertina: il membro `_COVER`, altrimenti il primo.
 *
 * I [GroupHint] arrivano dall'estrazione; senza, il nome base si ricava dal nome del file, ma non il modello: niente raffiche.
 */
object GroupingRules {

  private const val PAIR_WINDOW_MS = 2_000L
  private const val BURST_GAP_MS = 1_000L

  private val PAIR_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif")

  fun propose(candidates: List<GroupCandidate>): List<ProposedGroup> {
    val unique = candidates.distinctBy { it.id }
    val used = HashSet<MediaId>()
    val groups = ArrayList<ProposedGroup>()

    // ---- RAW+JPEG
    val byBase = unique.groupBy { it.folderKey to NameParts.of(it.displayName).stem }
    for ((_, list) in byBase.entries.sortedBy { it.key.second }) {
      val raws = list.filter { isRaw(it) }
      val images = list.filter { !isRaw(it) && NameParts.of(it.displayName).extension in PAIR_IMAGE_EXTENSIONS && it.kind != MediaKind.VIDEO }
      if (raws.size != 1 || images.size != 1) continue
      val raw = raws[0]
      val image = images[0]
      if (kotlin.math.abs(raw.takenAtMs - image.takenAtMs) >= PAIR_WINDOW_MS) continue
      groups += ProposedGroup(MediaGroupKind.RAW_JPEG_PAIR, listOf(image.id, raw.id), cover = image.id)
      used += image.id
      used += raw.id
    }

    // ---- Raffiche
    val burstCandidates = unique.filter { c ->
      c.kind == MediaKind.PHOTO || c.kind == MediaKind.MOTION_PHOTO
    }.filter { !isRaw(it) }
    val byKey = HashMap<Triple<String, String, String>, MutableList<Pair<GroupCandidate, NameParts>>>()
    for (c in burstCandidates) {
      val parts = NameParts.of(c.displayName)
      val index = c.hint?.burstIndex ?: parts.burstIndex
      val model = c.hint?.cameraModel?.trim()?.lowercase(Locale.ROOT)
      if (index == null || parts.style == null || model.isNullOrEmpty()) continue
      byKey.getOrPut(Triple(c.folderKey, parts.key, model)) { ArrayList() } += c to parts
    }
    for ((_, members) in byKey.entries.sortedBy { it.key.second }) {
      if (members.size < 2 || members.any { it.first.id in used }) continue
      val indexes = members.map { (c, p) -> c.hint?.burstIndex ?: p.burstIndex!! }
      if (indexes.toSet().size != members.size) continue // due scatti con lo stesso numero: ambiguo
      val ordered = members.sortedBy { (c, p) -> c.hint?.burstIndex ?: p.burstIndex!! }
      val strict = ordered.first().second.style == NameParts.Style.PARENTHESES
      var run = ArrayList<GroupCandidate>().also { it += ordered.first().first }
      val runs = ArrayList<List<GroupCandidate>>()
      for (k in 1 until ordered.size) {
        val previous = run.last()
        val current = ordered[k].first
        val gap = current.takenAtMs - previous.takenAtMs
        val sameStyle = ordered[k].second.style == ordered.first().second.style
        val consecutive = sameStyle && gap in 0 until BURST_GAP_MS && (!strict || gap > 0)
        if (consecutive) {
          run += current
        } else {
          runs += run
          run = ArrayList<GroupCandidate>().also { it += current }
        }
      }
      runs += run
      for (r in runs) {
        if (r.size < 2) continue
        val cover = r.firstOrNull { c -> NameParts.of(c.displayName).isCover } ?: r.first()
        groups += ProposedGroup(MediaGroupKind.BURST, r.map { it.id }, cover.id)
        r.forEach { used += it.id }
      }
    }
    return groups
  }

  private fun isRaw(c: GroupCandidate): Boolean = c.kind == MediaKind.RAW_PHOTO || NameParts.of(c.displayName).extension == "dng"
}
