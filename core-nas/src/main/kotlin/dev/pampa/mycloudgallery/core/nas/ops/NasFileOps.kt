package dev.pampa.mycloudgallery.core.nas.ops

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable

// Fase 4B (docs/fase4-progetto.md §1.2 "core-nas (P12, 4B): primitive di scrittura", D2, D24, D25; formato-nas.md §9 punto 7):
// le PRIMITIVE di scrittura sul NAS. Solo interfaccia, tipi e fake (P0-B); l'implementazione su smbj la fa P12.
//
// **Solo `core-nasops` vede `NasFileOps`.** Tre barriere, tutte verificate:
//  1. il test di architettura (`Phase4ArchitectureTest`, core-common): nessun sorgente fuori da `core-nas` e `core-nasops` nomina
//     un tipo `...NasFileOps` o il marcatore [DestructiveNasAccess];
//  2. il marcatore stesso: `NasFileOps` richiede un'adesione esplicita (`@RequiresOptIn`, livello ERRORE) che compila solo dove il
//     build la passa come argomento del compilatore (`core-nas` e `core-nasops`): anche chi ricevesse un'istanza senza nominarne il
//     tipo (per inferenza) non compila;
//  3. il grafo dei moduli: nessun modulo che legge il NAS o mostra la Libreria raggiunge `core-nasops`.
//
// Separato da `NasMediaReader` (lettura) e da `NasFileSystem` di core-trust (tre operazioni): chi legge non ha in mano niente che
// modifichi. Le letture che servono a decidere (stat, elenco, apertura) le fa `core-nasops` con il `NasMediaReader`.

/**
 * Marcatore di adesione: la dichiarazione modifica i file del NAS. Usarla senza l'argomento `-opt-in` del compilatore (solo
 * `core-nas` e `core-nasops`) e' un errore di compilazione. Non si risolve con `@OptIn` altrove: il test di architettura vieta il
 * nome del marcatore fuori dai due moduli.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Le primitive di scrittura del NAS (NasFileOps) le usa solo core-nasops: nessun dato letto dal NAS puo' far modificare i file.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class DestructiveNasAccess

/**
 * Le primitive di scrittura del NAS per UN dispositivo ([ownerDeviceId]). Ogni metodo apre (o riusa) la sessione dell'account
 * per la share secondo il [Purpose], passa dal cancello di rete, dall'interruttore e dalla recinzione D33 come la lettura, e non
 * lancia mai per un errore del NAS: [NasResult].
 *
 * **Cosa non puo' fare, per costruzione** (violarlo e' un errore di programmazione: `IllegalArgumentException`, non un
 * [NasResult]):
 *  - **eliminare un media**: l'eliminazione definitiva ([deleteIfUnchanged], [deleteOwnTemp]) vale solo dentro `.mycloudgallery/`
 *    (cestino e temporanei propri, indice proprio). Un media si toglie solo spostandolo nel cestino ([trashMove]), mai con una
 *    cancellazione: il peggio che un errore possa fare e' un file nel cestino per 30 giorni;
 *  - **sostituire**: nessuna operazione sovrascrive mai un file esistente (rinomina e pubblicazione danno `Collision`);
 *  - **toccare l'area dell'app dalle rinomine dei media**: [rename], [mkdirs] e [publishTemp] lavorano su percorsi di media
 *    (fuori da `.mycloudgallery/`); l'unica uscita dall'area dell'app e' la rinomina di un file del cestino verso il suo posto
 *    d'origine (ripristino).
 *
 * I percorsi sono sempre [SharePath] gia' validati. Il file temporaneo di un'operazione sta in
 * `.mycloudgallery/tmp/<ownerDeviceId>-<resto>` (`OwnFile.Temp` di core-trust): lo costruisce `core-nasops` e lo scrive nel diario
 * **prima** della chiamata; qui si controlla solo che sia davvero un temporaneo di [ownerDeviceId].
 */
@DestructiveNasAccess
interface NasFileOps {

  /** Il dispositivo proprietario dei temporanei (`tmp/<id>-<resto>`) e del cestino (`trash/<id>/files/<id>`) di questa istanza. */
  val ownerDeviceId: String

  /**
   * Crea [dir] e le cartelle che mancano (sempre fuori da `.mycloudgallery/`). "Esiste gia'" come cartella = successo (la
   * ripresa e' idempotente); esiste come file = `NasError.Exists`.
   */
  suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit>

  /**
   * Rinomina o sposta [from] in [to] **nella stessa share**, senza mai sostituire. Con [expect] controlla, sullo stesso handle e
   * prima del `SET_INFO`, dimensione, data di modifica e `fileId` (se noti) del **file** (le cartelle non si confrontano): se il
   * file non e' piu' quello confermato dall'utente (E-D13, E-N11) risponde [RenameOutcome.SourceChanged] e non fa niente.
   * La cartella di [to] deve esistere (`NasError.NotFound`: il chiamante fa prima [mkdirs]). Una rinomina che cambia solo le
   * maiuscole non si fa in un colpo: la rifiuta come [RenameOutcome.Collision] (E-N7), il chiamante passa da un nome temporaneo.
   */
  suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome>

  /**
   * Copia lato server [from] di [fromShare] nel temporaneo [temp] di [toShare] (tra share, D24). Il temporaneo non deve esistere
   * ([CopyOutcome.TempExists]). Con [expect] controlla il file sorgente come [rename]. Se il server rifiuta la copia
   * (`NOT_SUPPORTED`, `ACCESS_DENIED`) risponde [CopyOutcome.NotSupported]: ripiego con [openTemp] e lettura dal telefono (E-D12).
   * Non modifica mai la sorgente.
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
   * Crea il temporaneo [temp] in [share] (esclusivo, `FILE_CREATE`) e restituisce lo scrittore, per il ripiego dal telefono
   * (E-D12) e, dalla Fase 5, per il caricamento. `NasError.Exists` se c'e' gia': il chiamante lo toglie con [deleteOwnTemp] o lo
   * riconosce dal diario. Lo scrittore resta del chiamante, che lo chiude.
   */
  suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter>

  /**
   * Pubblica il temporaneo [temp] come [final] (stessa share): rinomina **senza sostituire**, creando le cartelle che mancano a
   * [final]. `Collision` restituisce il controllo al chiamante (nome alternativo, E-D1x); `TempMissing` e' il segnale della
   * risposta persa (D24: "temporaneo assente + destinazione con dimensione e hash giusti = pubblicato").
   */
  suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome>

  /**
   * Mette [path] nel cestino della **stessa** share: rinomina verso `trash/<ownerDeviceId>/files/<trashId>` (D25), creando le
   * cartelle del cestino. Un file o una cartella intera (una sola rinomina, E-D10). [expect] come in [rename]. Mai nell'area
   * dell'app: [path] e' un percorso di media. [trashId] e' un id da 128 bit in base32 (`TrustIds`).
   */
  suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome>

  /**
   * Elimina definitivamente [path] **solo se ha ancora dimensione [size]**, con un solo handle: apre, controlla la dimensione
   * sullo stesso handle, poi `delete-on-close` (formato §9.3, revisione B7). Vale solo dentro `.mycloudgallery/` (cestino
   * proprio, indice proprio); mai un media. Un file con un'altra dimensione e' [DeleteOutcome.SizeChanged], una cartella
   * [DeleteOutcome.NotAFile].
   *
   * R3 (revisione 4B): con [expect] anche la data di modifica e il `fileId` (se noti da entrambe le parti) si controllano sullo stesso
   * handle: un file diverso da quello che il diario ricorda, anche della stessa dimensione, e' [DeleteOutcome.SizeChanged] e resta.
   * Un punto di rianalisi (collegamento) non si cancella mai: [DeleteOutcome.NotAFile].
   */
  suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose, expect: NasStamp? = null): NasResult<DeleteOutcome>

  /**
   * R3 (revisione 4B): l'identita' (`fileId`) della cartella che contiene **sul disco** la cartella [dir] del **proprio** cestino, come
   * la dice il server nella voce `..` di [dir]. Per una cartella vera e' quella da cui ci si e' arrivati; per un collegamento simbolico
   * che il server presenta come cartella (Samba) e' il genitore della destinazione: la pulizia la confronta e non scende dove non torna.
   * `Ok(null)` se il server non lo dice. Solo lettura.
   */
  suspend fun parentIdentity(share: String, dir: SharePath, purpose: Purpose): NasResult<Long?>

  /** Elimina il temporaneo proprio [temp] (qualunque dimensione). Assente = [DeleteOutcome.Missing]. */
  suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome>

  /**
   * Toglie [dir] solo se e' vuota: dopo uno spostamento di cartella tra share (fuori da `.mycloudgallery/`), o per svuotare una
   * cartella del **proprio** cestino ([NasOpsPaths.isOwnTrashTree], E-D10) dopo averne eliminato i file. Mai con contenuto.
   */
  suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome>

  /**
   * Lo spazio della share. Tocca i dischi: solo su richiesta dell'utente (Spazio NAS), prima di uno spostamento tra share
   * (E-D5) o dopo un `DISK_FULL` (E-R15).
   */
  suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo>
}

/** Lo scrittore di un temporaneo (E-D12, Fase 5): scritture a offset sullo stesso handle, poi `finish` che controlla la lunghezza. */
@DestructiveNasAccess
interface NasTempWriter : Closeable {
  val temp: SharePath

  /** Scrive [len] byte di [buf] da [off] a [offset] del file. Un offset oltre la fine attuale e' un errore di programmazione. */
  suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit>

  /** Svuota le scritture e controlla, sullo stesso handle, che il file sia lungo [expectedSize]. */
  suspend fun finish(expectedSize: Long): NasResult<TempFinish>

  /** Chiude senza finire: il temporaneo resta (lo tolgono la ripresa dal diario o la pulizia dei propri file). */
  override fun close()
}

sealed interface TempFinish {
  /** Lunghezza giusta; [stamp] e' il timbro del temporaneo. */
  data class Complete(val stamp: NasStamp) : TempFinish

  data class SizeMismatch(val actual: Long) : TempFinish
}

/** Esiti di [NasFileOps.rename] e [NasFileOps.trashMove]. */
sealed interface RenameOutcome {
  /** Fatto; [stamp] e' il timbro dell'elemento dopo la rinomina (il `fileId` non cambia), se il server lo dice. */
  data class Renamed(val stamp: NasStamp?) : RenameOutcome

  /** La destinazione esiste gia' (o la rinomina cambierebbe solo le maiuscole). Niente e' cambiato. */
  data object Collision : RenameOutcome

  data object SourceMissing : RenameOutcome

  /** Il file non e' quello atteso ([NasStamp] diverso): spostato, modificato o sostituito da un altro (E-D13, E-N11). */
  data class SourceChanged(val actual: NasStamp?) : RenameOutcome

  /** Aperto da un altro programma (`SHARING_VIOLATION`, E-N13): si riprova piu' tardi. */
  data object Locked : RenameOutcome
}

/** Esiti di [NasFileOps.copyToTemp]. */
sealed interface CopyOutcome {
  data class Copied(val bytes: Long, val stamp: NasStamp) : CopyOutcome

  data object SourceMissing : CopyOutcome

  data class SourceChanged(val actual: NasStamp?) : CopyOutcome

  data object TempExists : CopyOutcome

  /** Copia lato server non disponibile o rifiutata: si ripiega sul telefono come attivita' pesante (E-D12). */
  data object NotSupported : CopyOutcome

  data object Locked : CopyOutcome
}

/** Esiti di [NasFileOps.publishTemp]. */
sealed interface PublishOutcome {
  data class Published(val stamp: NasStamp?) : PublishOutcome

  data object Collision : PublishOutcome

  data object TempMissing : PublishOutcome

  data object Locked : PublishOutcome
}

/** Esiti di [NasFileOps.deleteIfUnchanged] e [NasFileOps.deleteOwnTemp]. */
sealed interface DeleteOutcome {
  data object Deleted : DeleteOutcome

  data object Missing : DeleteOutcome

  /** La dimensione non e' piu' quella attesa (o, con il timbro atteso, la data o il `fileId`): non si cancella (E-D4, R3). */
  data class SizeChanged(val actual: Long) : DeleteOutcome

  data object NotAFile : DeleteOutcome

  data object Locked : DeleteOutcome
}

/** Esiti di [NasFileOps.rmdirIfEmpty]. */
sealed interface RmdirOutcome {
  data object Removed : RmdirOutcome

  data object NotEmpty : RmdirOutcome

  data object Missing : RmdirOutcome

  data object Locked : RmdirOutcome
}

/**
 * Lo spazio di una share (`FileFsFullSizeInformation`).
 *
 * @property callerFreeBytes lo spazio libero **per l'account**: se e' minore di [freeBytes] c'e' una quota (E-N13).
 */
data class NasSpaceInfo(val totalBytes: Long, val freeBytes: Long, val callerFreeBytes: Long) {
  init {
    require(totalBytes >= 0 && freeBytes >= 0 && callerFreeBytes >= 0) { "Spazio negativo." }
  }

  /** L'account ha una quota piu' stretta del disco. */
  val quotaLimited: Boolean get() = callerFreeBytes < freeBytes
}
