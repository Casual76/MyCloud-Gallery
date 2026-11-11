package dev.pampa.mycloudgallery.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** Una voce del vocabolario: il glifo e la famiglia (il colore) di una cosa dell'app. */
@Immutable
data class GalleryIconSpec(val icon: ImageVector, val family: GalleryIconFamily)

/**
 * Il vocabolario delle icone della galleria (I3, fase I): **la stessa cosa ha lo stesso segno ovunque** (Impostazioni, Backup, Raccolte, fogli).
 * Le icone dicono la cosa (la nuvola con la freccia per il backup, lo scudo per la sicurezza, il volto per le persone) e la famiglia ne da' il
 * colore. Tutte Material Rounded, gia' nell'APK con l'engine: nessuna dipendenza nuova.
 *
 * Chi ha una cosa che qui non c'e' sceglie un glifo Rounded e una [GalleryIconFamily] esistente; se la cosa torna in piu' pagine, la aggiunge qui.
 */
object GalleryIcons {
  // Foto e Libreria.
  val Library = GalleryIconSpec(Icons.Rounded.PhotoLibrary, GalleryIconFamily.Photos)
  val Collections = GalleryIconSpec(Icons.Rounded.Collections, GalleryIconFamily.Photos)
  val Quality = GalleryIconSpec(Icons.Rounded.HighQuality, GalleryIconFamily.Photos)

  // Backup e NAS.
  val Backup = GalleryIconSpec(Icons.Rounded.CloudUpload, GalleryIconFamily.Backup)
  val Nas = GalleryIconSpec(Icons.Rounded.Dns, GalleryIconFamily.Backup)
  val ExternalCopy = GalleryIconSpec(Icons.Rounded.SdStorage, GalleryIconFamily.Backup)
  val Schedule = GalleryIconSpec(Icons.Rounded.Schedule, GalleryIconFamily.Backup)
  val Battery = GalleryIconSpec(Icons.Rounded.BatteryChargingFull, GalleryIconFamily.Backup)
  val Rules = GalleryIconSpec(Icons.Rounded.Rule, GalleryIconFamily.Backup)

  // Persone e famiglia.
  val People = GalleryIconSpec(Icons.Rounded.Face, GalleryIconFamily.People)
  val Family = GalleryIconSpec(Icons.Rounded.FamilyRestroom, GalleryIconFamily.People)
  val Account = GalleryIconSpec(Icons.Rounded.AccountCircle, GalleryIconFamily.People)
  val Share = GalleryIconSpec(Icons.Rounded.IosShare, GalleryIconFamily.People)

  // Ricordi e luoghi.
  val Memories = GalleryIconSpec(Icons.Rounded.AutoAwesome, GalleryIconFamily.Memories)
  val Map = GalleryIconSpec(Icons.Rounded.Map, GalleryIconFamily.Places)

  // Ricerca e IA.
  val Search = GalleryIconSpec(Icons.Rounded.Search, GalleryIconFamily.Search)

  // Sicurezza.
  val Security = GalleryIconSpec(Icons.Rounded.Shield, GalleryIconFamily.Security)
  val Keys = GalleryIconSpec(Icons.Rounded.Key, GalleryIconFamily.Security)
  val Hidden = GalleryIconSpec(Icons.Rounded.VisibilityOff, GalleryIconFamily.Security)

  // Rete.
  val Network = GalleryIconSpec(Icons.Rounded.Wifi, GalleryIconFamily.Network)
  val Vpn = GalleryIconSpec(Icons.Rounded.VpnLock, GalleryIconFamily.Network)
  val Cellular = GalleryIconSpec(Icons.Rounded.SignalCellularAlt, GalleryIconFamily.Network)

  // Sistema.
  val Devices = GalleryIconSpec(Icons.Rounded.Devices, GalleryIconFamily.System)
  val Widgets = GalleryIconSpec(Icons.Rounded.Widgets, GalleryIconFamily.System)
  val Advanced = GalleryIconSpec(Icons.Rounded.Tune, GalleryIconFamily.System)
  val About = GalleryIconSpec(Icons.Rounded.Info, GalleryIconFamily.System)

  // Pericolo.
  val Delete = GalleryIconSpec(Icons.Rounded.Delete, GalleryIconFamily.Danger)
}
