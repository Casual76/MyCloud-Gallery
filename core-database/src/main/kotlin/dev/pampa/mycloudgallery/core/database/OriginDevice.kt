package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup

/**
 * Il **dispositivo d'origine** di un media (colonna derivata `media.origin_device` di v6, regola B di D14: "stesso dispositivo" di un
 * sicuro). Si ricava dalle posizioni, quindi lo riempiono la migrazione 5 -> 6 e il catalogo (`MediaCatalog.derive`) con **questa**
 * funzione e nessun'altra (`Migration5To6` ne chiede la forma alla stessa [ofNasKey]).
 *
 * - [PHONE]: il media ha una copia **non assente** sul telefono di questo dispositivo;
 * - `backup:<cartella>`: altrimenti, una copia non assente in una cartella `Backup/<cartella>/` di un'**area privata** (la cartella che
 *   il backup di un dispositivo sceglie una volta, F5-D6). Solo la radice vera `Backup/`: la recinzione di prova del debug (D13) scrive sotto
 *   un'altra radice che la release non ha e che questo modulo (nel dex della release, `check.ps1`) non nomina: in debug quei media non hanno un
 *   dispositivo, e quelli del telefono si. `<cartella>` e' la forma di
 *   confronto (NFC, minuscolo) come nelle chiavi delle posizioni. La cartella `Area comune` (le copie del guardiano) non e' un
 *   dispositivo. Con piu' cartelle vince quella che viene prima in ordine alfabetico (una sola risposta, uguale ovunque);
 * - NULL: nessuna delle due. In particolare **marca e modello della fotocamera** (EXIF, ultimo ripiego di §2.2 del progetto) non stanno
 *   nel catalogo (`version_metadata` non li conserva): finche' nessuno li salva, questi media non hanno un dispositivo. Il prefisso
 *   `cam:` e' riservato a quel ripiego.
 *
 * **Due etichette per lo stesso telefono.** Un media che ha perso la copia sul telefono ("Libera spazio") passa da [PHONE] a
 * `backup:<la cartella di questo dispositivo>`: chi confronta "stesso dispositivo" (P6) tratta `backup:<propria cartella>` come [PHONE]
 * (la propria cartella la conosce `AlbumFamily.backupFolderName`; il catalogo no, e non gli serve). Mai nei log: nomina un dispositivo.
 */
object OriginDevice {

  /** Una copia sul telefono di questo dispositivo. */
  const val PHONE: String = "phone"

  /** Il prefisso di una cartella `Backup/<cartella>/`. */
  const val BACKUP_PREFIX: String = "backup:"

  /** Riservato al ripiego dell'EXIF (marca e modello): oggi nessuno lo scrive. */
  const val CAMERA_PREFIX: String = "cam:"

  private const val BACKUP_ROOT = "backup"

  /** `BackupPaths.COMMON_AREA_FOLDER` in forma di confronto: le copie del guardiano non sono di nessun dispositivo. */
  private const val COMMON_AREA_FOLDER = "area comune"

  /** Quante parti ha una chiave di posizione del NAS: `nas`, account, share, percorso. */
  private const val KEY_PARTS = 4

  /** L'etichetta di una cartella di backup gia' in forma di confronto. */
  fun backup(folderKey: String): String = BACKUP_PREFIX + folderKey

  /**
   * L'etichetta di una **chiave di posizione** del NAS (`Location.Nas.key`: `nas:<account>:<share>:<percorso in forma di confronto>`),
   * o null se il file non sta in `Backup/<cartella>/...` o la cartella non e' un dispositivo.
   * Il file sta almeno dentro la cartella del dispositivo (`Backup/<cartella>/<nome>`).
   */
  fun ofNasKey(locationKey: String): String? {
    val parts = locationKey.split(':', limit = KEY_PARTS)
    if (parts.size != KEY_PARTS || parts[0] != "nas") return null
    val segments = parts[3].split('/')
    if (segments.firstOrNull() != BACKUP_ROOT || segments.size < 3) return null
    val folder = segments[1]
    if (folder.isEmpty() || folder == COMMON_AREA_FOLDER) return null
    return backup(folder)
  }

  /** L'etichetta di un media dalle sue posizioni; [areas] dice quali share sono aree private. Vedi il commento della classe. */
  fun of(locations: Collection<MediaLocation>, areas: NasAreaLookup): String? {
    val live = locations.filter { it.state.exists }
    if (live.any { it.location is Location.Phone }) return PHONE
    return live.mapNotNull { located ->
      val nas = located.location as? Location.Nas ?: return@mapNotNull null
      if (areas.areaOf(nas.account, nas.shareKey) != NasArea.PRIVATE) return@mapNotNull null
      ofNasKey(nas.key.value)
    }.minOrNull()
  }
}

/**
 * Dove stanno le copie presenti sul NAS di un media (colonna derivata `media.copy_areas` di v6): una maschera di bit. Come
 * [OriginDevice] la riempiono la migrazione e il catalogo con la stessa regola. L'area dipende dalla share della posizione
 * (`nas_share.area`): in un'area privata si entra con le credenziali del proprio account, quindi "area privata" e' sempre la propria.
 */
object CopyAreas {

  /** Una copia presente in una share dichiarata area privata. */
  const val PRIVATE: Int = 1

  /** Una copia presente nell'area comune. */
  const val COMMON: Int = 2

  /** Una copia presente in una share di cui non si conosce l'area (non e' privata, nel dubbio). */
  const val OTHER: Int = 4

  fun of(locations: Collection<MediaLocation>, areas: NasAreaLookup): Int {
    var mask = 0
    for (located in locations) {
      if (!located.isPresent) continue
      val nas = located.location as? Location.Nas ?: continue
      mask = mask or when (areas.areaOf(nas.account, nas.shareKey)) {
        NasArea.PRIVATE -> PRIVATE
        NasArea.COMMON -> COMMON
        null -> OTHER
      }
    }
    return mask
  }

  fun has(mask: Int, area: Int): Boolean = mask and area != 0
}
