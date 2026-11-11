package dev.pampa.mycloudgallery.feature.backup.contract

import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

// Fase 5A (docs/fase5-progetto.md §1.5, §2.1, §2.6, D7, D30; riga P5 del §4.4): le PORTE che le schermate di feature-backup leggono e
// che `:app` (P7) lega. Sono di feature-backup perche' in core-backup non c'e' (ancora) chi produca un elenco di `SourceStatus` ne' chi
// elenchi le cartelle del telefono: lo dicono il report di P5 e `docs/fase5-progetto.md` §1.5. Ogni porta e' **opzionale per Dagger**
// (`di/BackupOptionalBindings`): finche' `:app` non le lega le schermate usano i null-object di `noop`, che dicono "non configurato".
// Solo numeri e codici: nessuno di questi tipi porta nomi di file, percorsi di share o account (i nomi delle cartelle del telefono sono
// dati dell'utente: si mostrano, mai nei log).

/**
 * Cio' che la pagina Backup mostra: lo stato del backup e la riga di ogni sorgente.
 *
 * `:app` lo compone: [status] e' `BackupEngine.status`; [sources] viene dai conteggi del catalogo (`BackupCatalog.safetyCounts`), dalle
 * sorgenti scelte (`BackupSettings.sources`) e dal diario (`BackupStore.counts`).
 */
interface BackupOverview {
  val status: StateFlow<BackupStatus>

  /** Una riga per sorgente, nell'ordine in cui si mostrano (fotocamera e screenshot per prime, poi le cartelle aggiuntive). */
  val sources: StateFlow<List<SourceStatus>>
}

/**
 * Una cartella del telefono che si puo' scegliere come sorgente (§2.1: "Impostazioni → Backup elenca le altre cartelle del MediaStore con
 * numero e GB": app di messaggistica, Quick Share, altre cartelle di `DCIM` e `Pictures`, la microSD).
 *
 * @property media quanti media ci sono; [bytes] quanto pesano (le dimensioni vere del MediaStore).
 */
data class PhoneFolder(val source: BackupSource, val media: Int, val bytes: Long) {
  init {
    require(media >= 0 && bytes >= 0) { "Conteggi negativi." }
  }
}

/** Le cartelle del telefono con numero e peso. `:app` la lega a `MediaStoreSource`; la lettura puo' durare, quindi e' una chiamata. */
fun interface PhoneFolders {
  suspend fun folders(): List<PhoneFolder>
}

/**
 * I3 (fase I): le ultime foto di ogni sorgente, per le copertine della pagina Backup e di «Cosa salvare». `:app` la lega al catalogo (una lettura,
 * dal piu' recente). Solo identita': gli id possono essere di media nascosti o eliminati, e chi li mostra li passa dalla Libreria visibile.
 */
fun interface SourceNewestMedia {
  /** Per ogni sorgente di [sources] (per [BackupSource.id]), al piu' [perSource] media, dal piu' recente. */
  suspend fun newest(sources: List<BackupSource>, perSource: Int): Map<String, List<MediaId>>
}

/**
 * I3: le porte delle copertine, le stesse delle Raccolte. [library] e' la Libreria visibile (mai un nascosto ne' un eliminato), [loader] la cache
 * delle miniature della griglia, [newest] le ultime foto di ogni cartella. Opzionale: senza, le sorgenti hanno la piastrella della famiglia.
 */
class BackupCoverPorts(val newest: SourceNewestMedia, val library: ViewerItems, val loader: ThumbnailLoader)

/** Le regole del backup che l'utente tocca da qui (le altre sono nelle attivita'). Del dispositivo, non personali. */
interface BackupRules {
  /** D34: "Proteggi subito le foto nuove" (Q7 (a), predefinita accesa): una foto appena scattata si carica con il Wi-Fi di casa. */
  val protectNewPhotos: StateFlow<Boolean>

  suspend fun setProtectNewPhotos(on: Boolean)
}

/**
 * Cosa c'e' gia' sul NAS con il nome del dispositivo (E-B6, E-B16): una cartella `Backup/<nome>/` con [files] file (una reinstallazione,
 * un telefono nuovo, un secondo dispositivo con lo stesso nome).
 */
data class ExistingFolder(val files: Int) {
  init {
    require(files >= 0) { "Conteggio negativo." }
  }
}

/**
 * La proposta per la cartella del dispositivo (D7): il nome ripulito per `SharePath` ([suggested]), se sul NAS c'e' gia' una cartella con
 * quel nome ([existing]: si propone "Continua lì") e, in quel caso, un nome libero ([alternative]: "Nuova cartella").
 */
data class DeviceFolderProposal(val suggested: DeviceFolder, val existing: ExistingFolder?, val alternative: DeviceFolder?)

/** La proposta per la cartella del dispositivo; `:app` la lega al nome del dispositivo e a un elenco di `Backup/` (sola lettura). Null = non ancora disponibile. */
fun interface DeviceFolderProposals {
  suspend fun propose(): DeviceFolderProposal?
}
