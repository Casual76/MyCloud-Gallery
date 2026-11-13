package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * L'accesso ai media letto dai permessi **adesso** (docs/android.md tema 4, accesso parziale): mai memorizzato, si rilegge
 * a ogni [refresh] (`onResume`, dopo una richiesta, a ogni giro e a ogni lotto di una scansione completa).
 *
 * - [MediaAccess.FULL]: su Android 13+ `READ_MEDIA_IMAGES` **e** `READ_MEDIA_VIDEO`; fino ad Android 12
 *   `READ_EXTERNAL_STORAGE`. Uno solo dei due su 13+ vale come parziale: con meta' libreria visibile non si possono
 *   dedurre assenze.
 * - [MediaAccess.PARTIAL]: "foto selezionate" (`READ_MEDIA_VISUAL_USER_SELECTED`, Android 14+) o uno solo dei due.
 * - `mediaLocation`: `ACCESS_MEDIA_LOCATION` (D9).
 *
 * I nomi dei permessi sono scritti come testo: alcuni non esistono al livello minimo di API e il controllo su un permesso
 * sconosciuto risponde semplicemente "negato".
 */
class AndroidPhoneAccessReader(
  context: Context,
  private val volumeNames: () -> Set<String> = { MediaStore.getExternalVolumeNames(context.applicationContext) },
) : PhoneAccessReader {

  private val appContext = context.applicationContext
  private val flow = MutableStateFlow(read())

  override val state: StateFlow<PhoneAccess> = flow.asStateFlow()

  private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

  fun onChange(listener: () -> Unit): AutoCloseable {
    listeners += listener
    return AutoCloseable { listeners -= listener }
  }

  override fun refresh() {
    val next = read()
    val changed = next != flow.value
    flow.value = next
    if (changed) listeners.forEach { it() }
  }

  private fun read(): PhoneAccess {
    val sdk = Build.VERSION.SDK_INT
    val images = granted(READ_MEDIA_IMAGES)
    val videos = granted(READ_MEDIA_VIDEO)
    val full = if (sdk >= Build.VERSION_CODES.TIRAMISU) images && videos else granted(READ_EXTERNAL_STORAGE)
    val partial = !full && (
      (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(READ_MEDIA_VISUAL_USER_SELECTED)) ||
        (sdk >= Build.VERSION_CODES.TIRAMISU && (images || videos))
      )
    val media = when {
      full -> MediaAccess.FULL
      partial -> MediaAccess.PARTIAL
      else -> MediaAccess.NONE
    }
    val volumes = try {
      volumeNames().filter(::isValidMediaStoreVolume).toSet()
    } catch (e: RuntimeException) {
      emptySet()
    }
    return PhoneAccess(media, mediaLocation = granted(ACCESS_MEDIA_LOCATION), volumes = volumes)
  }

  private fun granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

  private companion object {
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION"
  }
}
