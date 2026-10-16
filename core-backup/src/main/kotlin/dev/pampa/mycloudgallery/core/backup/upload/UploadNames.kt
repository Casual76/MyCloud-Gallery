package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** I nomi e i codici del caricamento (D6, E-B3, §2.2 "Rete", §2.6): puri, senza effetti. */
object UploadNames {

  /**
   * Il nome del tentativo [attempt] dopo collisioni con file **diversi** (E-B3): 0 = il nome previsto, 1 = "nome (2)", 2 = "nome (3)"...
   * nella stessa cartella, l'estensione resta. Lo stesso schema dell'esecutore di riferimento e dei nomi alternativi della 4B.
   */
  fun nameFor(planned: SharePath, attempt: Int): SharePath {
    require(attempt >= 0) { "Tentativo negativo." }
    if (attempt == 0) return planned
    val name = planned.name
    val dot = name.lastIndexOf('.')
    val alt = if (dot > 0) "${name.substring(0, dot)} (${attempt + 1})${name.substring(dot)}" else "$name (${attempt + 1})"
    return planned.parent()?.child(alt) ?: SharePath.of(alt)
  }

  /** Il problema del giro per un errore del NAS (§2.2 "Rete", §2.6): il giro si ferma, l'elemento no. */
  fun problemFor(error: NasError): BackupProblemCode = when (error) {
    NasError.NasFull -> BackupProblemCode.NAS_FULL
    NasError.QuotaExceeded -> BackupProblemCode.QUOTA
    is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing -> BackupProblemCode.AUTH
    NasError.PermissionDenied -> BackupProblemCode.READ_ONLY_AREA
    else -> BackupProblemCode.NAS_UNREACHABLE
  }
}
