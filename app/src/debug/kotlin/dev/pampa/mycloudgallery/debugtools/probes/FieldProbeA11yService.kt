package dev.pampa.mycloudgallery.debugtools.probes

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

/**
 * SOLO build debug (Fase 10, P8, D24, E-AC10, F185): il servizio di accessibilita' **di prova che non e' uno strumento di accessibilita'**
 * (`isAccessibilityTool="false"`, come un'app malevola con il permesso). Serve a verificare, sull'emulatore e sul telefono, che con
 * `accessibilityDataSensitive` sulla finestra dell'app (Android 14+) un servizio qualunque **non legge niente**: a ogni cambio di contenuto
 * (al piu' ogni 1,5 secondi) conta i nodi della finestra attiva e li scrive in logcat con il tag `FieldProbeA11y`, con il pacchetto: quanti sono, quanti
 * hanno un testo o una descrizione, quanti sono toccabili. **Mai il contenuto**: solo numeri. Si accende a mano dalle impostazioni di
 * accessibilita' o con `adb shell settings put secure enabled_accessibility_services` (vedi `docs/decisioni.md`, Fase 10 P8).
 *
 * Non e' nel manifest principale e la release non lo ha (`tools/check.ps1`, passo dei resti di debug: prefisso `FieldProbe`).
 */
class FieldProbeA11yService : AccessibilityService() {

  private var lastReadAt = 0L

  override fun onServiceConnected() {
    val tool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) serviceInfo?.isAccessibilityTool else null
    GalleryLog.i(TAG) { "collegato: strumento di accessibilita'=$tool" }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val type = event?.eventType ?: return
    if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastReadAt < MIN_GAP_MS) return
    lastReadAt = now
    val root = rootInActiveWindow
    if (root == null) {
      GalleryLog.i(TAG) { "finestra ${event?.packageName}: nessuna radice leggibile" }
      return
    }
    val count = Count()
    try {
      walk(root, count, depth = 0)
    } finally {
      root.recycleIfNeeded()
    }
    GalleryLog.i(TAG) { "finestra ${event?.packageName}: nodi=${count.nodes} conTesto=${count.withText} toccabili=${count.clickable}" }
  }

  override fun onInterrupt() = Unit

  private class Count {
    var nodes = 0
    var withText = 0
    var clickable = 0
  }

  private fun walk(node: AccessibilityNodeInfo, count: Count, depth: Int) {
    count.nodes++
    if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) count.withText++
    if (node.isClickable) count.clickable++
    if (depth >= MAX_DEPTH) return
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      try {
        walk(child, count, depth + 1)
      } finally {
        child.recycleIfNeeded()
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun AccessibilityNodeInfo.recycleIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
  }

  private companion object {
    const val TAG = "FieldProbeA11y"
    const val MIN_GAP_MS = 1_500L
    const val MAX_DEPTH = 40
  }
}
