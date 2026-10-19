package dev.pampa.mycloudgallery.core.nas.ops.backup

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable
import java.text.Normalizer

// Fase 5A (docs/fase5-progetto.md §1.2 "core-nas: primitive in sola aggiunta", D2, D4, D5, D33; formato-nas.md §9 punto 8):
// le primitive del BACKUP. Contratto, null-object e fake di P0; la vista vera sopra `SmbNasFileOps` (con `reopenTemp` e il
// punto di ripresa) e' di P2. Fase 5B (P0-B, D17, D18): `archiveVersion` e `restoreVersion`, le uniche due primitive che SPOSTANO un
// file visibile (sempre con il suo timbro, mai verso un nome occupato); contratto, null-object e fake sono di P0-B, la vista SMB vera
// e' di P8 (`SmbNasFileOps.renameVersion`, provata sul simulatore Samba in `ops/backup/SmbVersionRenamesTest`).
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
//
// **Cosa aggiunge la 5B: due rinomine vincolate, nessuna cancellazione.** Le versioni (D17) spostano il file attuale in `Versioni/`
// ([NasBackupOps.archiveVersion]) e, per "Ripristina originale" (D18), riportano l'originale al suo posto
// ([NasBackupOps.restoreVersion]). Sono rinomine dentro la stessa share, **senza sostituire**, con il timbro del file **obbligatorio** e
// controllato sullo stesso handle prima del `SET_INFO`; una delle due estremita' sta sempre sotto `Versioni/` ([VersionPaths]) e l'altra e'
// un percorso di media, mai un'area dell'app. I byte non si perdono (la rinomina li conserva, con lo stesso `fileId`) e nessun file si
// toglie: una versione intermedia si elimina solo dal foglio di `feature-nas` (cestino del NAS, D20). L'insieme dei metodi di questa vista
// e' chiuso e lo controlla `Phase5BArchitectureTest`: chi aggiungesse una rinomina libera, un cestino o una cancellazione lo fa fallire.

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
   *
   * R3 della revisione 5A: e' l'unica primitiva del backup che cambia byte gia' scritti, quindi accorcia solo il temporaneo **del punto di
   * ripresa**: [tempId] e' la sua identita' ([TempPoint.fileId], letta dallo scrittore sul suo handle e scritta nel diario con il punto).
   * Sullo stesso handle, prima di accorciare, un'identita' diversa, piu' di un nome (un collegamento fisico), un punto di rianalisi o una
   * `tmp/` che non e' la cartella vera danno [ReopenOutcome.Foreign] e niente cambia.
   */
  suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome>

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

  /**
   * 5B (D17): archivia la versione **attuale** [from] spostandola in [to], sotto `Versioni/` della stessa [share] ([VersionPaths]).
   * Rinomina **senza sostituire** (`RenameOutcome.Collision` se [to] c'e' gia', anche per le sole maiuscole), con [expect]
   * **obbligatorio**: dimensione, data di modifica e `fileId` si controllano sullo stesso handle, prima del `SET_INFO`; un file diverso
   * (modificato dal PC, sostituito) e' `SourceChanged` e niente si muove (`SOURCE_CHANGED` del diario, D17). Una cartella al posto del
   * file e' `SourceChanged` (non si archiviano cartelle). La cartella di [to] deve esistere (`NasError.NotFound`: il chiamante fa prima
   * [mkdirs]), come per ogni rinomina della 4B. Il timbro dopo la rinomina ha lo stesso `fileId`: il chiamante lo scrive nel diario e la
   * scansione riconosce l'archiviazione per identita' (E-N1). Mai verso o da `.mycloudgallery/` ([VersionPaths.requireArchive]).
   * **Non toglie niente**: se il lavoro si interrompe a meta', il file e' al vecchio o al nuovo nome, con tutti i suoi byte.
   */
  suspend fun archiveVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome>

  /**
   * 5B (D18, "Ripristina originale"): l'inversa di [archiveVersion]. Riporta [from], sotto `Versioni/`, al suo posto [to] (un percorso di
   * media fuori da `Versioni/`) nella stessa [share] ([VersionPaths.requireRestore]). Stesse garanzie: senza sostituire (il solito
   * percorso deve essere libero: l'attuale va prima in `Versioni/`, e' lo scambio `SWAP` del diario), timbro obbligatorio controllato
   * sullo stesso handle, la cartella di [to] deve esistere, nessun file si toglie.
   */
  suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome>

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
   * D5: svuota le scritture e legge lunghezza e identita' del file **sullo stesso handle** ([TempPoint]). Il chiamante scrive nel diario
   * il punto di ripresa solo se la lunghezza e' quella che si aspetta (ogni 64 MiB), e con il punto l'identita' (R3 della revisione 5A),
   * che la riapertura confrontera' prima di accorciare.
   */
  suspend fun checkpoint(): NasResult<TempPoint>

  /** Svuota le scritture e controlla, sullo stesso handle, che il file sia lungo [expectedSize]. */
  suspend fun finish(expectedSize: Long): NasResult<TempFinish>

  /** Chiude senza finire: il temporaneo resta, citato dal diario del backup (la pulizia dei propri file non lo tocca, D33). */
  override fun close()
}

/**
 * Un punto di ripresa letto **sullo stesso handle** dello scrittore (D5, R3 della revisione 5A): fin dove arriva il file ([length]) e quale
 * file e' ([fileId], l'identita' del server; null se non la dice: senza identita' la ripresa non si fa).
 */
data class TempPoint(val length: Long, val fileId: Long?)

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

  /**
   * R3 della revisione 5A: al nome del temporaneo non c'e' il temporaneo del punto di ripresa: un'altra identita', piu' di un nome (un
   * collegamento fisico), un punto di rianalisi, o `tmp/` non e' la cartella vera. **Niente e' cambiato** (nessun accorciamento, nessuna
   * scrittura): il chiamante ricomincia da capo con un temporaneo nuovo.
   */
  data object Foreign : ReopenOutcome
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

/**
 * Dove stanno le versioni precedenti (D17, Q2 (a)): `<share>/Versioni/` alla **radice di ogni share**, che rispecchia i percorsi
 * ("Versioni/Backup/S25 di Mario/2026/10/IMG_1234 (originale).jpg"). E' una cartella normale della share, non firmata, visibile dal PC
 * (N1 di `docs/formato-nas.md`). Puro e senza effetti: lo usano il fake, la vista SMB e core-backup per rifiutare un percorso sbagliato
 * **prima** di toccare il NAS (un errore di programmazione, `IllegalArgumentException`, mai un `NasResult`).
 */
object VersionPaths {

  /** Il nome della cartella alla radice di ogni share. */
  const val VERSIONS_DIR: String = "Versioni"

  private fun isVersionsName(component: String): Boolean =
    Normalizer.normalize(component, Normalizer.Form.NFKC).equals(VERSIONS_DIR, ignoreCase = true)

  /** Vero se [path] sta dentro `Versioni/` (non la cartella stessa): anche con maiuscole o forme di compatibilita' diverse, come l'area dell'app. */
  fun isInVersions(path: SharePath): Boolean = path.components.size > 1 && isVersionsName(path.components[0])

  /** Vero se [path] e' la cartella `Versioni/` stessa. */
  fun isVersionsRoot(path: SharePath): Boolean = path.components.size == 1 && isVersionsName(path.components[0])

  /**
   * Il percorso in `Versioni/` che rispecchia [path] (un percorso di media fuori da `Versioni/`): `Versioni/` piu' le sue componenti. La
   * cartella di una versione e' quella del file che archivia; il nome ("(originale)", "(versione del ...)") lo sceglie P8.
   */
  fun mirrorOf(path: SharePath): SharePath {
    requireMediaOutsideVersions(path)
    return SharePath.of(VERSIONS_DIR, *path.components.toTypedArray())
  }

  /** [NasBackupOps.archiveVersion]: da un file di media fuori da `Versioni/` a un percorso sotto `Versioni/`. */
  fun requireArchive(from: SharePath, to: SharePath) {
    requireMediaOutsideVersions(from)
    require(isInVersions(to) && NasOpsPaths.isMediaPath(to)) { "Si archivia sotto Versioni/." }
  }

  /** [NasBackupOps.restoreVersion]: da un percorso sotto `Versioni/` a un file di media fuori da `Versioni/`. */
  fun requireRestore(from: SharePath, to: SharePath) {
    require(isInVersions(from) && NasOpsPaths.isMediaPath(from)) { "Si ripristina da Versioni/." }
    requireMediaOutsideVersions(to)
  }

  private fun requireMediaOutsideVersions(path: SharePath) {
    require(NasOpsPaths.isMediaPath(path) && !isInVersions(path) && !isVersionsRoot(path)) {
      "Un file di media fuori da Versioni/ e fuori dall'area dell'app."
    }
  }
}
