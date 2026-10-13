package dev.pampa.mycloudgallery.core.model

/**
 * Caratteristiche di una versione che non cambiano il suo [MediaKind] ma cambiano come si mostra o si
 * filtra (piano 3.1, tabella "Tratti" di `docs/fase3-progetto.md` §2.2). Sono metadati della versione,
 * come la data: una modifica che ritaglia un panorama produce una versione senza il tratto.
 *
 * Nel database si salvano come **maschera di bit** (`version_metadata.traits`): una colonna sola, letta
 * insieme al resto dei metadati. Per questo ogni tratto ha, oltre al codice stabile, un [bit] stabile:
 * come il codice si **aggiunge**, non si cambia e non si riusa (`StableCodesTest`).
 */
enum class MediaTrait(override val code: String, val bit: Int) : StableCode {
  /** XMP GPano o rapporto dei lati di almeno 2,5:1. */
  PANORAMA("panorama", 1 shl 0),

  /** Foto sferica (proiezione `equirectangular`). */
  SPHERE("sphere", 1 shl 1),

  /** JPEG con gain map (`hdrgm:Version`): Ultra HDR di Android 14+. */
  ULTRA_HDR("ultra_hdr", 1 shl 2),

  /** Video con trasferimento ST 2084 (PQ, HDR10/HDR10+) o HLG. */
  HDR_VIDEO("hdr_video", 1 shl 3),

  /** Registrazione dello schermo (`Screen_Recording_*`, `DCIM/Screen recordings/`). */
  SCREEN_RECORDING("screen_recording", 1 shl 4),
  ;

  companion object {
    /** La maschera salvata di un insieme di tratti. */
    fun toBits(traits: Set<MediaTrait>): Int = traits.fold(0) { acc, t -> acc or t.bit }

    /**
     * I tratti di una maschera salvata. I bit sconosciuti (scritti da una versione piu' nuova
     * dell'app) si ignorano: un tratto in meno si mostra peggio, non si rompe nulla.
     */
    fun fromBits(bits: Int): Set<MediaTrait> = entries.filterTo(LinkedHashSet()) { bits and it.bit != 0 }
  }
}
