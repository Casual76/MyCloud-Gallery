package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** I nomi e i codici del caricamento (D6, E-B3, §2.2 "Rete", §2.6): puri, senza effetti. */
object UploadNames {

  /**
   * Il nome del tentativo [attempt] dopo collisioni con file **diversi** (E-B3): 0 = il nome previsto, 1 = "nome (2)", 2 = "nome (3)"...
   * nella stessa cartella, l'estensione resta. Lo stesso schema dell'esecutore di riferimento, del guardiano e dei nomi alternativi della
   * 4B; un nome che con " (n)" supererebbe i 255 byte si accorcia (R1 della revisione 5A): vedi [BackupPaths.alternateName].
   */
  fun nameFor(planned: SharePath, attempt: Int): SharePath = BackupPaths.alternateName(planned, attempt)

  /** Il problema del giro per un errore del NAS (§2.2 "Rete", §2.6): il giro si ferma, l'elemento no. */
  fun problemFor(error: NasError): BackupProblemCode = when (error) {
    NasError.NasFull -> BackupProblemCode.NAS_FULL
    NasError.QuotaExceeded -> BackupProblemCode.QUOTA
    is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing -> BackupProblemCode.AUTH
    NasError.PermissionDenied -> BackupProblemCode.READ_ONLY_AREA
    else -> BackupProblemCode.NAS_UNREACHABLE
  }
}
