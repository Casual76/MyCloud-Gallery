package dev.pampa.mycloudgallery.core.security.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Le tre protezioni di finestra come composable, da mettere DENTRO la schermata che ne ha bisogno: restano
 * attive finche' la schermata e' in composizione e si tolgono da sole quando esce.
 *
 * ```
 * @Composable fun HiddenScreen() { SecureScreen(); ... }          // Nascosti (E-S5), QR
 * @Composable fun PasswordScreen() { SensitiveScreen(); ... }     // password: FLAG_SECURE + anti-sovrapposizioni
 * @Composable fun ConfirmSheet() { OverlayShield(); ... }         // conferme (cancella, condividi, approva)
 * ```
 *
 * Valgono per la FINESTRA che contiene il composable: dentro un `Dialog` e' la finestra del dialog (che ha
 * le sue flag), altrimenti quella dell'activity.
 *
 * **Dialog e FLAG_SECURE.** Il `Dialog` di Compose gestisce `FLAG_SECURE` da solo, secondo
 * `DialogProperties.securePolicy`, e la rimette a ogni aggiornamento dei parametri: un [SecureScreen] dentro un
 * dialog verrebbe sovrascritto. Per un dialog con contenuto riservato (es. un QR) si usa
 * `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`; con la politica predefinita (`Inherit`) il
 * dialog eredita la flag dalla finestra dell'activity se la schermata sotto ha gia' un [SecureScreen].
 * [OverlayShield], invece, funziona anche dentro il dialog (il Dialog non tocca quelle impostazioni).
 */
@Composable
fun SecureScreen(enabled: Boolean = true) {
  val view = LocalView.current
  DisposableEffect(view, enabled) {
    val handle = if (enabled) hostWindow(view)?.let { WindowProtection.of(it).acquireSecureContent() } else null
    onDispose { handle?.close() }
  }
}

/** Difesa dal tapjacking: gli overlay delle altre app si nascondono e i tocchi "oscurati" si scartano. */
@Composable
fun OverlayShield(enabled: Boolean = true) {
  val view = LocalView.current
  DisposableEffect(view, enabled) {
    val handle = if (enabled) hostWindow(view)?.let { WindowProtection.of(it).acquireOverlayShield() } else null
    onDispose { handle?.close() }
  }
}

/** [SecureScreen] + [OverlayShield]: per le schermate della password. */
@Composable
fun SensitiveScreen(enabled: Boolean = true) {
  SecureScreen(enabled)
  OverlayShield(enabled)
}

/** La finestra che contiene [view]: quella del dialog se la vista sta in un dialog, altrimenti quella dell'activity. */
internal fun hostWindow(view: View): Window? {
  var parent: Any? = view.parent
  while (parent is View) {
    if (parent is DialogWindowProvider) return parent.window
    parent = parent.parent
  }
  return view.context.findActivity()?.window
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
