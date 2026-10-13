package dev.pampa.mycloudgallery.core.model

/**
 * I flag di una cella della griglia, in una maschera di bit: e' cio' che i badge e i filtri leggono
 * dall'indice della timeline senza unire versioni, metadati e dati personali (D2, D15).
 *
 * La maschera e' un **campo derivato** di `media` (`cell_flags`), calcolato da
 * [dev.pampa.mycloudgallery.core.model.domain.TimelineRules.cellFlags] e riscritto dal catalogo nella
 * stessa transazione che cambia cio' da cui dipende. Finisce anche nell'istantanea su file della
 * timeline: i **bit sono stabili** come i codici di [StableCode] (si aggiungono, non si cambiano, non si
 * riusano; `CellFlagsTest`).
 */
object CellFlags {

  /** La versione corrente non e' l'originale (modifica o correzione): badge "modificata". */
  const val EDITED: Int = 1 shl 0

  /** La data di scatto e' una stima (nome del file, data del file): "stimata" (E-M8). */
  const val DATE_ESTIMATED: Int = 1 shl 1

  /** META ha rinunciato (file corrotto, E-M10): "non leggibile", mai bloccante. */
  const val UNREADABLE: Int = 1 shl 2

  /** Ha un luogo (intestazioni con i luoghi, info). */
  const val HAS_PLACE: Int = 1 shl 3

  /** Motion photo: si anima tenendo premuto. */
  const val MOTION: Int = 1 shl 4

  /** Panorama: la griglia giustificata lo tiene largo. */
  const val PANORAMA: Int = 1 shl 5

  /** Ultra HDR: il viewer passa allo spazio colore HDR. */
  const val ULTRA_HDR: Int = 1 shl 6

  /** Preferito (dato personale, copiato qui solo per la cella). */
  const val FAVORITE: Int = 1 shl 7

  /**
   * Membro di un gruppo (raffica, RAW+JPEG) che **non** lo rappresenta: fuori dall'indice, la griglia
   * mostra solo la copertina (D20).
   */
  const val GROUP_MEMBER: Int = 1 shl 8

  /** Rappresenta un gruppo nell'indice (la copertina, o chi la sostituisce se non e' visibile). */
  const val GROUP_COVER: Int = 1 shl 9

  /** Tutti i flag con il loro nome, per i test di stabilita' e la Diagnostica (solo numeri). */
  val ALL: Map<String, Int> = linkedMapOf(
    "EDITED" to EDITED,
    "DATE_ESTIMATED" to DATE_ESTIMATED,
    "UNREADABLE" to UNREADABLE,
    "HAS_PLACE" to HAS_PLACE,
    "MOTION" to MOTION,
    "PANORAMA" to PANORAMA,
    "ULTRA_HDR" to ULTRA_HDR,
    "FAVORITE" to FAVORITE,
    "GROUP_MEMBER" to GROUP_MEMBER,
    "GROUP_COVER" to GROUP_COVER,
  )

  fun has(flags: Int, flag: Int): Boolean = flags and flag == flag
}
