package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// IL PUNTATORE DELLA FINESTRA (Fase 10, P5, D12, D15; docs/fase10-progetto.md §2.3). **File nuovo** in `adaptive/`. `GalleryWindow.pointer` dice se c'e' un mouse (DeX con mouse e tastiera, tablet
// con il mouse): si legge dall'elenco dei dispositivi d'ingresso e si rilegge solo quando il sistema ne annuncia uno nuovo o uno tolto (nessun lavoro a ogni fotogramma, nessun evento di puntatore
// da guardare). Un dispositivo **virtuale** (il touchpad di DeX sul telefono, le tastiere a schermo) non e' un mouse; il tocco resta il caso normale e il valore di ripiego quando non si puo' leggere.

/** Come si riconosce un mouse fra i dispositivi d'ingresso: pura, provata sulla JVM. */
internal object PointerSources {
  /** Un dispositivo e' un mouse se espone la sorgente "mouse" e non e' virtuale. */
  fun isMouse(sources: Int, virtual: Boolean): Boolean = !virtual && (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE

  /** Il puntatore per un insieme di dispositivi, dati come (sorgenti, virtuale): un mouse basta. */
  fun of(devices: Collection<Pair<Int, Boolean>>): PointerKind =
    if (devices.any { (sources, virtual) -> isMouse(sources, virtual) }) PointerKind.MOUSE else PointerKind.TOUCH

  /** Legge i dispositivi d'ingresso di adesso; [PointerKind.TOUCH] se il sistema non risponde. */
  fun read(context: Context): PointerKind = runCatching {
    val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    of(manager.inputDeviceIds.asList().mapNotNull { id -> manager.getInputDevice(id)?.let { it.sources to it.isVirtual } })
  }.getOrDefault(PointerKind.TOUCH)
}

/** Il puntatore di adesso, che cambia quando un mouse si collega o si scollega. Economico: una lettura e un ascoltatore per chiamata. */
@Composable
internal fun rememberPointerKind(): PointerKind {
  val context = LocalContext.current
  var kind by remember(context) { mutableStateOf(PointerSources.read(context)) }
  DisposableEffect(context) {
    val manager = runCatching { context.getSystemService(Context.INPUT_SERVICE) as InputManager }.getOrNull()
    val listener = object : InputManager.InputDeviceListener {
      override fun onInputDeviceAdded(deviceId: Int) {
        kind = PointerSources.read(context)
      }

      override fun onInputDeviceRemoved(deviceId: Int) {
        kind = PointerSources.read(context)
      }

      override fun onInputDeviceChanged(deviceId: Int) {
        kind = PointerSources.read(context)
      }
    }
    val registered = manager != null && runCatching { manager.registerInputDeviceListener(listener, null) }.isSuccess
    onDispose { if (registered) runCatching { manager?.unregisterInputDeviceListener(listener) } }
  }
  return kind
}
