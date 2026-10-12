package dev.pampa.mycloudgallery.core.qr

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.qr.scanner.CameraAccess
import dev.pampa.mycloudgallery.core.qr.scanner.CameraFeed
import dev.pampa.mycloudgallery.core.qr.scanner.ScanDispatcher
import dev.pampa.mycloudgallery.core.qr.scanner.ScanOutcome
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerActions
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerContent
import dev.pampa.mycloudgallery.core.qr.scanner.ScannerUi
import dev.pampa.mycloudgallery.core.qr.scanner.TorchUi
import dev.pampa.mycloudgallery.core.qr.scanner.rememberCameraPermission
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import kotlinx.coroutines.delay

/**
 * Lo scanner dei QR dentro l'app (D4): mirino della fotocamera (CameraX), decodifica con ZXing, gestione
 * del permesso della fotocamera e ripiego manuale.
 *
 * **Cosa riceve [accept].** Ogni testo letto da un QR (o scritto a mano, senza spazi ai lati e senza a-capo)
 * che non superi [QrLimits.MAX_CHARS] caratteri: uno piu' lungo non arriva, e lo scanner mostra
 * `NOT_OURS`. Il verdetto decide: su [ScanVerdict.Accept] lo scanner si ferma e annuncia "Codice letto"; su
 * [ScanVerdict.Reject] mostra il motivo sotto il mirino e continua a cercare; su [ScanVerdict.Ignore] non
 * mostra niente. Lo stesso QR tenuto davanti alla fotocamera non viene riconsegnato per un paio di secondi.
 * Il testo e' un segreto (un invito, una chiave): lo scanner non lo registra, non lo scrive, non lo mostra.
 * [accept] e [onCancel] arrivano sempre sul thread principale.
 *
 * **Permesso della fotocamera.** Mai chiesto prima di aprire lo scanner. Prima richiesta: una spiegazione e
 * il bottone che apre la domanda di sistema. Negato: si spiega, si offre di riprovare, e subito sotto c'e' il
 * campo per incollare o scrivere il codice. "Non chiedere piu'": il sistema non mostra piu' la domanda, quindi
 * il bottone diventa "Apri le impostazioni" (e al ritorno lo scanner si accende da solo se e' stato sbloccato),
 * sempre con il campo a mano. Nessuna fotocamera, o che non parte: solo il campo. In ogni caso [onCancel] e'
 * sempre a portata.
 *
 * **Privacy.** Nessuna immagine viene salvata, ne' tenuta oltre il fotogramma in analisi, ne' inviata: lo
 * scanner ha solo il flusso di analisi di CameraX e non ha il permesso di scrivere file. La finestra prende
 * `FLAG_SECURE` e la difesa dalle sovrapposizioni finche' lo scanner e' aperto: dal mirino puo' passare
 * un invito, e il campo a mano ne riceve il testo.
 *
 * **Torcia** se la fotocamera ne ha una. **Accessibilita'**: il mirino ha una descrizione, la torcia e' un
 * interruttore con il suo stato, e la riga sotto il mirino e' una regione "viva": il lettore di schermo
 * annuncia i rifiuti e "Codice letto" a lettura avvenuta. Il bersaglio di ogni bottone e' di almeno 48 dp e la
 * pagina scorre con il testo ingrandito.
 */
@Composable
fun QrScanner(
  accept: (String) -> ScanVerdict,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SensitiveScreen()

  val permission = rememberCameraPermission()
  var manualOpen by rememberSaveable { mutableStateOf(false) }
  // Il testo a mano NON si salva con lo stato dell'istanza: e' un segreto, non deve finire in un Bundle.
  var manualText by remember { mutableStateOf("") }
  var rejection by remember { mutableStateOf<ScanRejection?>(null) }
  var rejectionCount by remember { mutableIntStateOf(0) }
  var read by remember { mutableStateOf(false) }
  var cameraFailed by remember { mutableStateOf(false) }
  var torchAvailable by remember { mutableStateOf(false) }
  var torchOn by remember { mutableStateOf(false) }

  val currentAccept by rememberUpdatedState(accept)
  val currentOnCancel by rememberUpdatedState(onCancel)
  val dispatcher = remember { ScanDispatcher(accept = { currentAccept(it) }, nowMs = SystemClock::elapsedRealtime) }
  val haptic = LocalHapticFeedback.current

  fun handle(outcome: ScanOutcome) {
    when (outcome) {
      ScanOutcome.Accepted -> {
        read = true
        rejection = null
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
      }
      is ScanOutcome.Rejected -> {
        rejection = outcome.reason
        rejectionCount++
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
      }
      ScanOutcome.Nothing -> Unit
    }
  }

  // Il motivo del rifiuto resta qualche secondo, poi torna il suggerimento. Il contatore fa ripartire
  // l'attesa anche quando il motivo e' lo stesso di prima.
  LaunchedEffect(rejectionCount) {
    if (rejectionCount > 0) {
      delay(REJECTION_VISIBLE_MS)
      rejection = null
    }
  }

  // Una fotocamera che non parte vale come "nessuna fotocamera": resta il campo a mano.
  val access = if (cameraFailed && permission.access == CameraAccess.Granted) CameraAccess.NoCamera else permission.access

  val ui = ScannerUi(
    access = access,
    manualOpen = manualOpen,
    manualText = manualText,
    rejection = rejection,
    read = read,
    torch = TorchUi(available = torchAvailable, on = torchOn),
  )
  val actions = ScannerActions(
    onRequestPermission = permission.request,
    onOpenSettings = permission.openSettings,
    onOpenManual = { manualOpen = true },
    onCloseManual = { manualOpen = false },
    onManualTextChange = { manualText = it },
    onManualSubmit = { handle(dispatcher.onManual(manualText)) },
    onToggleTorch = { torchOn = !torchOn },
    onCancel = { currentOnCancel() },
  )

  ScannerContent(ui = ui, actions = actions, modifier = modifier) {
    CameraFeed(
      torch = torchOn,
      onTorchAvailable = { torchAvailable = it },
      onRead = { result: QrRead -> handle(dispatcher.onRead(result)) },
      onFailure = { cameraFailed = true },
    )
  }
}

private const val REJECTION_VISIBLE_MS = 5_000L
