package dev.pampa.mycloudgallery.feature.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.input.SensitiveClipboard
import java.util.Locale

/**
 * Cosa il viewer puo' fare con un media, in questo momento (D13, menu a capacita'): **solo cio' che funziona**. Una voce
 * che non si puo' eseguire non compare, invece di restare sullo schermo morta.
 *
 *  - Preferito e Nascondi servono le azioni locali (`LocalMediaActions`).
 *  - Condividi e Copia servono un media del telefono e le azioni di sistema (l'URI `content://media/...`).
 *  - Elimina dal telefono serve il cestino di sistema (Android 11+, D14); su Android 10 non c'e'.
 *  - Scarica sul telefono (Fase 4, D20) serve un media del NAS e un `PhoneDownload`.
 *  - Informazioni c'e' sempre.
 *
 * Versioni, correzioni, modifica con, sposta e rinomina arrivano con le loro fasi, senza cambiare la struttura.
 */
internal data class ViewerCapabilities(
  val favorite: Boolean,
  val hide: Boolean,
  val share: Boolean,
  val copy: Boolean,
  val trash: Boolean,
  val download: Boolean = false,
) {
  companion object {
    fun of(services: ViewerServices, ref: MediaRef): ViewerCapabilities {
      val phone = ref is MediaRef.Phone
      val intents = services.intents
      return ViewerCapabilities(
        favorite = services.actions != null,
        hide = services.actions != null,
        // Fase 5B: con il backup legato "Condividi" passa da `:app` e vale anche per un media solo sul NAS (E-V7: lo scarica in cache).
        share = (phone && intents != null) || services.backupActions?.capabilities?.share == true,
        copy = phone && intents != null,
        trash = phone && intents?.canTrash == true,
        download = ref is MediaRef.Nas && services.download?.canDownload(ref) == true,
      )
    }
  }
}

/** Cosa succede quando si sceglie un'azione. */
internal class ViewerActionHandlers(
  val onToggleFavorite: () -> Unit,
  val onToggleHidden: () -> Unit,
  val onShare: () -> Unit,
  val onCopy: () -> Unit,
  val onDelete: () -> Unit,
  val onInfo: () -> Unit,
  val onDownload: () -> Unit = {},
)

/**
 * Le voci del menu ⋮ in tre gruppi: preferito e nascondi; condividi, copia e informazioni; in fondo, dopo
 * un'interruzione, l'unica distruttiva ("Elimina dal telefono"). I gruppi vuoti non lasciano interruzioni doppie.
 *
 * @param secure il viewer e' aperto dai Nascosti: la voce e' "Mostra nella Libreria", non "Nascondi".
 * @param nasActions le voci "Sposta" e "Rinomina" di un media del NAS (Fase 4B): nel gruppo di Condividi, Copia e Informazioni.
 * @param nasDelete la voce "Elimina…" di un media del NAS: in fondo, con l'altra voce che cancella.
 * @param backupActions le voci del backup (Fase 5B, P12): Versioni, Ripristina originale, Correggi, Ruota, Modifica con…, nel gruppo di Condividi.
 * @param shareWithoutLocation "Rimuovi la posizione" e' acceso: la voce Condividi lo dice ("Condividi senza posizione").
 * @param onPlayMotion la voce "Riproduci il movimento" di una foto in movimento (Fase 6, D51): null = la pagina non e' una foto in
 *   movimento o il suo video non si riproduce (E-M16).
 * @param similarActions la voce "Trova simili" (Fase 6, P8, D30): vuota se il media e' nascosto o il viewer non ha la ricerca; vedi `similarMenuEntries`.
 */
internal fun viewerMenuEntries(
  resources: Resources,
  capabilities: ViewerCapabilities,
  isFavorite: Boolean,
  secure: Boolean,
  handlers: ViewerActionHandlers,
  nasActions: List<GalleryMenuEntry> = emptyList(),
  nasDelete: List<GalleryMenuEntry> = emptyList(),
  backupActions: List<GalleryMenuEntry> = emptyList(),
  shareWithoutLocation: Boolean = false,
  onPlayMotion: (() -> Unit)? = null,
  similarActions: List<GalleryMenuEntry> = emptyList(),
): List<GalleryMenuEntry> {
  val groups = mutableListOf<List<GalleryMenuEntry>>()
  groups.add(buildList<GalleryMenuEntry> {
    if (capabilities.favorite) {
      add(
        GalleryMenuEntry.Item(
          label = resources.getString(if (isFavorite) R.string.viewer_action_unfavorite else R.string.viewer_action_favorite),
          onClick = handlers.onToggleFavorite,
          icon = if (isFavorite) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
        ),
      )
    }
    if (capabilities.hide) {
      add(
        GalleryMenuEntry.Item(
          label = resources.getString(if (secure) R.string.viewer_action_unhide else R.string.viewer_action_hide),
          onClick = handlers.onToggleHidden,
          icon = if (secure) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
        ),
      )
    }
  })
  groups.add(buildList<GalleryMenuEntry> {
    if (onPlayMotion != null) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.motion_play), onPlayMotion, Icons.Rounded.MotionPhotosOn))
    }
    if (capabilities.share) {
      val shareLabel = if (shareWithoutLocation) R.string.viewer_backup_action_share_no_location else R.string.viewer_action_share
      add(GalleryMenuEntry.Item(resources.getString(shareLabel), handlers.onShare, Icons.Rounded.IosShare))
    }
    if (capabilities.copy) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_action_copy), handlers.onCopy, Icons.Rounded.ContentCopy))
    }
    if (capabilities.download) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_action_download), handlers.onDownload, Icons.Rounded.Download))
    }
    addAll(nasActions)
    addAll(backupActions)
    addAll(similarActions)
    add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_action_info), handlers.onInfo, Icons.Rounded.Info))
  })
  groups.add(buildList<GalleryMenuEntry> {
    if (capabilities.trash) {
      add(
        GalleryMenuEntry.Item(
          label = resources.getString(R.string.viewer_action_delete),
          onClick = handlers.onDelete,
          icon = Icons.Rounded.DeleteForever,
          destructive = true,
        ),
      )
    }
    addAll(nasDelete)
  })
  return groups.filter { it.isNotEmpty() }.flatMapIndexed { index, group ->
    if (index == 0) group else listOf(GalleryMenuEntry.GroupBreak) + group
  }
}

/** Le azioni rapide della capsula in basso: condividi, preferito, informazioni, elimina. */
internal fun viewerBarActions(
  resources: Resources,
  capabilities: ViewerCapabilities,
  isFavorite: Boolean,
  handlers: ViewerActionHandlers,
): List<GalleryBarAction> = buildList {
  if (capabilities.share) {
    add(GalleryBarAction(Icons.Rounded.IosShare, resources.getString(R.string.viewer_action_share), handlers.onShare))
  }
  if (capabilities.favorite) {
    add(
      GalleryBarAction(
        icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = resources.getString(if (isFavorite) R.string.viewer_action_unfavorite else R.string.viewer_action_favorite),
        onClick = handlers.onToggleFavorite,
      ),
    )
  }
  add(GalleryBarAction(Icons.Rounded.Info, resources.getString(R.string.viewer_action_info), handlers.onInfo))
  if (capabilities.trash) {
    add(GalleryBarAction(Icons.Rounded.DeleteForever, resources.getString(R.string.viewer_action_delete), handlers.onDelete, destructive = true))
  }
}

/** Le azioni di sistema che il viewer lancia: tutte esplicite, tutte a un tocco dell'utente. */
internal object ViewerSystem {

  /**
   * Mette negli appunti l'URI del media (non i byte). Se il viewer e' aperto dai Nascosti l'appunto e' marcato come
   * **sensibile** (`EXTRA_IS_SENSITIVE`): da Android 13 il sistema non ne mostra l'anteprima.
   */
  fun copyUri(context: Context, uri: Uri, label: String, sensitive: Boolean): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    // `newUri` chiede il tipo al provider; se il provider non risponde (URI revocato) l'appunto resta valido, senza tipo.
    val clip = try {
      ClipData.newUri(context.contentResolver, label, uri)
    } catch (_: RuntimeException) {
      ClipData.newRawUri(label, uri)
    }
    if (sensitive) {
      clip.description.extras = PersistableBundle().apply { putBoolean(SensitiveClipboard.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    return true
  }

  /** L'intento `geo:` per "Apri in Mappe" (D18): la posizione esce dall'app solo qui, con la conferma dell'utente. */
  fun mapsIntent(point: GeoPoint): Intent {
    val lat = String.format(Locale.US, "%.6f", point.latitude)
    val lon = String.format(Locale.US, "%.6f", point.longitude)
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
  }
}
