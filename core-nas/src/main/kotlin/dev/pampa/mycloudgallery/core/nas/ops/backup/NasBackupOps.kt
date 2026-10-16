package dev.pampa.mycloudgallery.core.nas.ops.backup

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable

// Fase 5A (docs/fase5-progetto.md §1.2 "core-nas: primitive in sola aggiunta", D2, D4, D5, D33; formato-nas.md §9 punto 8):
// le primitive del BACKUP. Contratto, null-object e fake di P0; la vista vera sopra `SmbNasFileOps` (con `reopenTemp` e il
// punto di ripresa) e' di P2, `archiveVersion`/`restoreVersion` arrivano nella 5B (P8).
//
// **Solo core-backup vede `NasBackupOps`.** Le stesse tre barriere delle primitive della 4B, con un marcatore suo:
//  1. il test di architettura (`Phase5ArchitectureTest`, core-common): nessun sorgente fuori da `core-nas` e `core-backup` nomina
//     `...NasBackupOps...` o il marcatore [AppendOnlyNasAccess];
//  2. il marcatore stesso: `@RequiresOptIn` di livello ERRORE, l'argomento del compilatore lo passano solo i due build;
//  3. il grafo: chi legge il NAS o mostra la Libreria non vede core-backup, e core-backup non vede ne' `NasFileOps` ne' core-nasops.
//
// **Cosa puo' fare il backup, per costruzione: solo AGGIUNGERE.** Creare cartelle, scrivere un proprio temporaneo
// `.mycloudgallery/tmp/<io>-bk-<id>`, copiarci dentro lato server, pubblicarlo con un nome nuovo **senza sostituire**, togliere i
// propri temporanei del backup, leggere lo spazio. Nessuna di queste toglie o cambia byte di un file visibile: niente rinomina libera,
// niente cestino, niente cancellazione di un media, nemmeno i temporanei delle operazioni della 4B (`-op-`). Il backup gira da solo,
// ogni notte, da dati locali e senza una conferma per file: deve essere INCAPACE di distruggere (D2).

/**
 * Marcatore di adesione: la dichiarazione scrive sul NAS **in sola aggiunta** (il backup). Usarla senza l'argomento `-opt-in` del
 * compilatore (solo `core-nas` e `core-backup`) e' un errore di compilazione; il test di architettura vieta il nome altrove.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Le primitive del backup (NasBackupOps) le usa solo core-backup: in sola aggiunta, mai da dati letti dal NAS.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class AppendOnlyNasAccess

/**
 * Le primitive del backup per UN dispositivo ([ownerDeviceId]): una **vista** delle primitive della 4B con gli stessi esiti
 * (`PublishOutcome`, `CopyOutcome`, `DeleteOutcome`, `TempFinish`, `NasSpaceInfo`) e la stessa sessione (cancello di rete,
 * interruttore, recinzione D33, sorvegliante), piu' [reopenTemp] per la ripresa dei file grandi (D5). Non lancia mai per un errore
 * del NAS: [NasResult]. La consegna [NasBackupOpsFactory] **solo** a core-backup; `NasSessionManager` non la espone.
 *
 * Ogni temporaneo e' un temporaneo **del backup di questo dispositivo** ([NasBackupPaths.isBackupTempOf]): un altro percorso e' un
 * errore di programmazione (`IllegalArgumentException`), come i percorsi fuori posto delle primitive della 4B. Il chiamante lo
 * costruisce con [NasBackupPaths.tempFor] e lo scrive nel diario **prima** della chiamata (D3).
 */
@AppendOnlyNasAccess
interface NasBackupOps {

  /** Il dispositivo proprietario dei temporanei `tmp/<id>-bk-<resto>` di questa istanza. */
  val ownerDeviceId: String

  /** Crea [dir] e le cartelle che mancano (sempre fuori da `.mycloudgallery/`). Esiste gia' come cartella = successo. */
  suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit>

  /**
   * Crea il temporaneo [temp] (esclusivo, `FILE_CREATE`) e restituisce lo scrittore. `NasError.Exists` se c'e' gia': il chiamante
   * lo riconosce dal diario (punto di ripresa: [reopenTemp]) o lo toglie con [deleteOwnTemp] e ricomincia da zero.
   */
  suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter>

  /**
   * D5, ripresa di un file grande: riapre il **proprio** temporaneo [temp] **senza crearlo**, lo accorcia a [keepBytes] (fine file
   * sullo stesso handle, `SET_INFO`) e restituisce lo scrittore pronto a continuare da li' ([BackupTempWriter.length] = [keepBytes]).
   * Assente: [ReopenOutcome.Missing]. Piu' corto di [keepBytes]: [ReopenOutcome.TooShort] e niente cambia (il chiamante riparte da
   * zero). Fidarsi del prefisso e' sicuro perche' la verifica completa (D4) viene comunque dopo.
   */
  suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, purpose: Purpose): NasResult<ReopenOutcome>

  /**
   * Copia lato server [from] di [fromShare] nel temporaneo [temp] di [toShare] (il guardiano, D9: dall'area comune all'area
   * privata). Il temporaneo non deve esistere; con [expect] la sorgente si controlla come nella 4B; non modifica mai la sorgente.
   */
  suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome>

  /**
   * Pubblica il temporaneo [temp] come [final] (stessa share): rinomina **senza sostituire**, creando le cartelle che mancano.
   * `Collision`: il nome e' occupato (si rilegge quello che c'e', D6, E-B3); `TempMissing`: il segnale della risposta persa (D3).
   */
  suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome>

  /** Toglie il **proprio** temporaneo del backup [temp] (qualunque dimensione). Assente = `DeleteOutcome.Missing`. */
  suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome>

  /** Lo spazio della share. Tocca i dischi: solo prima di un file oltre 1 GB o dopo un `DISK_FULL` (§2.2, E-R9). */
  suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo>
}

/**
 * Lo scrittore di un temporaneo del backup: scritture a offset sullo stesso handle, il **punto di ripresa** ([checkpoint], D5) e
 * [finish] che controlla la lunghezza. Resta del chiamante, che lo chiude.
 */
@AppendOnlyNasAccess
interface BackupTempWriter : Closeable {
  val temp: SharePath

  /** Dove arrivano i dati scritti finora su questo handle (0 dopo [NasBackupOps.openTemp], `keepBytes` dopo la riapertura). */
  val length: Long

  /** Scrive [len] byte di [buf] da [off] a [offset]. Un offset oltre [length] e' un errore di programmazione (lascerebbe un buco). */
  suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit>

  /**
   * D5: svuota le scritture e legge la lunghezza del file **sullo stesso handle**. Il chiamante scrive nel diario il punto di
   * ripresa solo se la lunghezza e' quella che si aspetta (ogni 64 MiB).
   */
  suspend fun checkpoint(): NasResult<Long>

  /** Svuota le scritture e controlla, sullo stesso handle, che il file sia lungo [expectedSize]. */
  suspend fun finish(expectedSize: Long): NasResult<TempFinish>

  /** Chiude senza finire: il temporaneo resta, citato dal diario del backup (la pulizia dei propri file non lo tocca, D33). */
  override fun close()
}

/** Esiti di [NasBackupOps.reopenTemp]. */
sealed interface ReopenOutcome {
  /** Riaperto e accorciato: lo scrittore continua da `keepBytes`. */
  class Reopened(val writer: BackupTempWriter) : ReopenOutcome {
    override fun toString(): String = "Reopened(${writer.length} B)"
  }

  /** Il temporaneo non c'e' (tolto, mai creato): si ricomincia da zero con `openTemp`. */
  data object Missing : ReopenOutcome

  /** Il temporaneo e' piu' corto del punto salvato (`keepBytes` oltre la lunghezza): non si tocca, si ricomincia da zero. */
  data class TooShort(val actual: Long) : ReopenOutcome

  /** Aperto da un altro programma (`SHARING_VIOLATION`): si riprova piu' tardi. */
  data object Locked : ReopenOutcome
}

/**
 * Dove stanno i temporanei del backup (D33): `.mycloudgallery/tmp/<dispositivo>-bk-<id>`, nella share di destinazione. Cartella col
 * punto e nessuna estensione: invisibili alla scansione, a DLNA e alle viste normali del PC; `OwnFile.Temp` di core-trust li
 * riconosce come temporanei propri (la pulizia dei propri file li lascia finche' il diario del backup li cita, porta `CitedTemps` di
 * core-nasops); la pulizia di core-nas (`SmbNasHousekeeping`) riconosce solo i suoi nomi da 32 cifre esadecimali e non li tocca.
 * Puro e senza effetti: lo usano il fake, la vista vera e core-backup.
 */
object NasBackupPaths {

  /** Il segno dei temporanei del backup dopo l'id del dispositivo (quelli della 4B hanno `-op-`). */
  const val TEMP_MARK: String = "bk"

  private val TOKEN = Regex("^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$")

  /**
   * Il temporaneo dell'elemento [itemId] del diario del backup di [deviceId]; [attempt] distingue i caricamenti ripetuti dello
   * stesso elemento se il chiamante lo vuole (0 = nessun suffisso). Nomi derivati da id, mai da testo letto dal NAS.
   */
  fun tempFor(deviceId: String, itemId: Long, attempt: Int = 0): SharePath {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
    require(itemId > 0) { "Id di elemento non valido." }
    require(attempt >= 0) { "Tentativo negativo." }
    val suffix = if (attempt == 0) "" else "-$attempt"
    return NasLayout.ROOT.child("tmp").child("$deviceId-$TEMP_MARK-$itemId$suffix")
  }

  /** Vero se [path] e' un temporaneo **del backup** di [deviceId] (mai uno delle operazioni della 4B ne' di un altro dispositivo). */
  fun isBackupTempOf(path: SharePath, deviceId: String): Boolean {
    if (!NasOpsPaths.isTempOf(path, deviceId)) return false
    val rest = path.name.removePrefix("$deviceId-")
    if (!rest.startsWith("$TEMP_MARK-")) return false
    return TOKEN.matches(rest.removePrefix("$TEMP_MARK-"))
  }
}
