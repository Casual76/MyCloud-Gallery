package dev.pampa.mycloudgallery.core.nasmedia.noop

import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeState
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanner
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object di core-nasmedia (release-safe): nessuna scansione, nessun congelamento. Falliscono chiusi: nessun giro
// risulta completo, quindi nessuna assenza puo' essere segnata.

/** Nessuna scansione: ogni giro si ferma subito come "non raggiungibile", mai completo. */
object NoNasScanner : NasScanner {
  override val status: StateFlow<NasScanStatus> = MutableStateFlow(NasScanStatus.Idle)

  override suspend fun scan(mode: ScanMode, budget: Duration): ScanReport = ScanReport(
    mode = mode, folders = 0, files = 0, unchanged = 0, adopted = 0, detected = 0, arriving = 0, skippedNames = 0,
    skippedSystem = 0, albumCovers = 0, notMedia = 0, busy = 0, vanished = 0, bytesRead = 0, complete = false,
    resumed = false, stoppedBy = ScanStop.UNAVAILABLE,
  )
}

/** Mai congelata, e nessun gesto ha effetto. */
object NeverFrozen : FreezeState {
  override val frozen: StateFlow<Freeze?> = MutableStateFlow(null)

  override suspend fun release(gesture: UserGesture): Boolean = false
}
