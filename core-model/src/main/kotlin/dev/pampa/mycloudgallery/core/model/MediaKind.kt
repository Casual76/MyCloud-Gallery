package dev.pampa.mycloudgallery.core.model

/**
 * Che cosa e' un singolo media (un file, con le sue versioni).
 *
 * **Raffiche e coppie RAW+JPEG non sono un "tipo"**: sono *piu' file* con byte diversi, ognuno con
 * le sue impronte, le sue posizioni e il suo stato di backup. Fonderli in un media solo vorrebbe dire
 * che "al sicuro" di uno copre l'altro, o che cancellarne uno li cancella entrambi. Restano media
 * distinti, uniti da un [MediaGroup] che la griglia mostra come un elemento solo (E-M6, E-M13).
 */
enum class MediaKind(override val code: String) : StableCode {
  /** JPEG, HEIC, PNG, WebP statico, BMP. */
  PHOTO("photo"),

  /** MP4, MOV, WebM, MKV. */
  VIDEO("video"),

  /** Foto con un video incorporato (Samsung, Google): la foto e la parte video sono un file solo (E-M5). */
  MOTION_PHOTO("motion_photo"),

  /** DNG e altri RAW: pesanti, spesso con un JPEG gemello nello stesso [MediaGroup]. */
  RAW_PHOTO("raw_photo"),

  /** GIF e WebP animati (E-M11). */
  ANIMATED_IMAGE("animated_image"),
  ;

  /** Se si guarda come un video (durata, riproduzione, fotogrammi chiave per l'analisi). */
  val isVideo: Boolean get() = this == VIDEO
}

/** Il tipo di un [MediaGroup]. */
enum class MediaGroupKind(override val code: String) : StableCode {
  /** Raffica: molti scatti in un secondo, mostrati come uno (E-M6). */
  BURST("burst"),

  /** Lo stesso scatto salvato come RAW e come JPEG (E-M13). */
  RAW_JPEG_PAIR("raw_jpeg_pair"),

  /**
   * Fase 4 (D21, E-L9): lo stesso video su telefono e NAS con dimensione e `qh1` uguali ma senza hash completo. Si
   * mostra **una** cella "da verificare" (copertina: la copia del telefono) finche' il passaggio `FULL_HASH` non
   * decide: stessi byte → unione d'identita', byte diversi → il gruppo si scioglie. Mai un'unione senza hash completo.
   */
  PROBABLE_DUPLICATE("probable_duplicate"),

  /**
   * Fase 5 (D36, `docs/fase5-progetto.md` §2.12, E-L10…E-L13): lo **stesso scatto con byte diversi** (ricompresso da un cloud,
   * EXIF riscritto, HEIC convertito) in una cella sola, con la copia migliore come copertina ([CellFlags.SIMILAR_COPIES]). Ogni
   * copia resta un media a se' (il backup carica sempre l'originale del telefono, "al sicuro" e' per media). Gruppo **derivato
   * locale**: ogni dispositivo lo ricalcola; "Separa" e "Mostra questa come principale" sono dati personali (core-personal).
   */
  SIMILAR_COPIES("similar_copies"),
}

/**
 * Un gruppo di media distinti che si mostrano come uno. [coverMediaId] e' quello che rappresenta il
 * gruppo nella griglia (il JPEG della coppia, lo scatto migliore della raffica); se manca lo sceglie
 * l'interfaccia.
 */
data class MediaGroup(
  val id: MediaGroupId,
  val kind: MediaGroupKind,
  val coverMediaId: MediaId? = null,
)

/**
 * Da dove viene un media, per i filtri rapidi della Libreria (screenshot, WhatsApp: piano 3.3) e per
 * le sorgenti del backup (5.1). Riguarda l'*origine del contenuto*, non il posto in cui si trova: uno
 * screenshot copiato sul NAS resta uno screenshot.
 */
enum class MediaSource(override val code: String) : StableCode {
  CAMERA("camera"),
  SCREENSHOT("screenshot"),
  WHATSAPP("whatsapp"),
  QUICK_SHARE("quick_share"),

  /** Tutto il resto, e "non lo so ancora". */
  OTHER("other"),
  ;

  /** Quanto dice: [OTHER] non dice nulla e in un'unione cede a qualunque sorgente piu' precisa. */
  val isSpecific: Boolean get() = this != OTHER
}
