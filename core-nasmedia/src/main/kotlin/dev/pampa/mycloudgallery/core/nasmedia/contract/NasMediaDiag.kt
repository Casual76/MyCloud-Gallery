package dev.pampa.mycloudgallery.core.nasmedia.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode

/**
 * I codici di Diagnostica della scansione (sezione `NAS_LIBRARY`), fissati da P0: li produce P5, li cataloga P11
 * (`feature-diagnostics`). Solo conteggi ed enumerati: mai nomi di file, cartelle, share o account.
 */
object NasMediaDiag {
  /** Ultimo giro: tipo, file, rilevati, adottati, durata, completo (F1, F3). */
  val NAS_SCAN_STATE = DiagCode("NAS_SCAN_STATE", setOf("E-N5"))

  /** Share congelata (D10): in evidenza, con "Ho controllato, continua" (C11, F9). */
  val NAS_SCAN_FROZEN = DiagCode("NAS_SCAN_FROZEN", setOf("C11", "E-S11"))

  /** Nomi non gestibili saltati (E-N6). */
  val NAS_SCAN_SKIPPED_NAMES = DiagCode("NAS_SCAN_SKIPPED_NAMES", setOf("E-N6"))

  /** Copertine degli album escluse (E-N14). */
  val NAS_SCAN_ALBUM_COVERS = DiagCode("NAS_SCAN_ALBUM_COVERS", setOf("E-N14"))

  /** Zero byte e falsi media (E-M12). */
  val NAS_SCAN_NOT_MEDIA = DiagCode("NAS_SCAN_NOT_MEDIA", setOf("E-M12"))

  /** File in arrivo, non ancora stabili per l'ora del NAS (E-N4). */
  val NAS_SCAN_ARRIVING = DiagCode("NAS_SCAN_ARRIVING", setOf("E-N4"))

  /** File bloccati dal PC, riprovati al giro dopo (E-N13). */
  val NAS_SCAN_BUSY = DiagCode("NAS_SCAN_BUSY", setOf("E-N13"))

  /** Cicli, punti di rianalisi, profondita' oltre 64 (E-N10). */
  val NAS_SCAN_LOOPS = DiagCode("NAS_SCAN_LOOPS", setOf("E-N10"))

  /** Cartelle oltre il tetto di 200.000 voci: assenze non segnate li' (E-N5). */
  val NAS_SCAN_TRUNCATED = DiagCode("NAS_SCAN_TRUNCATED", setOf("E-N5"))

  /** Il rilevamento fuori casa ha raggiunto il tetto di 50 MB (D22). */
  val NAS_SCAN_AWAY_CAP = DiagCode("NAS_SCAN_AWAY_CAP", setOf("D22"))

  val ALL: List<DiagCode> = listOf(
    NAS_SCAN_STATE, NAS_SCAN_FROZEN, NAS_SCAN_SKIPPED_NAMES, NAS_SCAN_ALBUM_COVERS, NAS_SCAN_NOT_MEDIA, NAS_SCAN_ARRIVING,
    NAS_SCAN_BUSY, NAS_SCAN_LOOPS, NAS_SCAN_TRUNCATED, NAS_SCAN_AWAY_CAP,
  )
}
