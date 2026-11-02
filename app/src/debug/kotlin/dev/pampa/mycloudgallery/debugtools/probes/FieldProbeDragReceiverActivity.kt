package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pampa.mycloudgallery.R
import java.io.File
import java.security.MessageDigest

/**
 * SOLO build debug (Fase 10, P6; docs/fase10-progetto.md §2.5, D20, D21, F168, F169): l'**app di prova "MCG prova (riceve)"** del trascinamento fra app.
 *
 * **Riceve** (F168): un riquadro dove si rilasciano foto e video trascinati da MyCloud Gallery (a schermo diviso, o in una finestra libera). Al rilascio chiede il permesso
 * (`requestDragAndDropPermissions`), poi per ogni indirizzo **misura** e **scrive**: lo schema e l'autorita', se si apre e quanti byte legge (con l'impronta SHA-256 abbreviata, per confrontarli con
 * l'originale), se la **scrittura** e' negata e se un permesso **persistente** viene rifiutato (C436: solo lettura, non persistente). **Mai un nome ne' un id**: solo numeri e forme.
 * **Trascina** (F169): un secondo riquadro che, tenuto premuto, trascina fino a tre foto delle cartelle di prova del telefono (`mcg-prova`) verso MyCloud Gallery (Libreria, album, Cartelle NAS):
 * si apre il foglio della ricezione e niente parte senza un tocco.
 *
 * Una sola activity, nello stesso processo dell'app (un processo a parte farebbe nascere una seconda `Application` con tutto il suo grafo): prova il gesto, il `ClipData`, i permessi e la
 * lettura dal provider dei documenti, ma **non** l'isolamento fra due utenti Android diversi (`CallerGuard` con un chiamante estraneo e' provato dalla JVM e dai tavoli del provider). Il resoconto
 * resta anche in `noBackupFilesDir/field-probe/drag-report.txt` (`adb pull`). Compare nel foglio come "MCG prova (riceve)". Solo in `app/src/debug`: la release non ha niente di tutto questo.
 */
class FieldProbeDragReceiverActivity : Activity() {

  private lateinit var zone: TextView
  private lateinit var report: TextView
  private val lines = ArrayList<String>()
  private var drops = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = dp(16)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }
    root.addView(TextView(this).apply {
      text = getString(R.string.probe_drag_title)
      textSize = 20f
      setTypeface(typeface, Typeface.BOLD)
    })
    zone = tile(getString(R.string.probe_drag_zone)).also { root.addView(it, tileParams()) }
    val source = tile(getString(R.string.probe_drag_source)).also { root.addView(it, tileParams()) }
    report = TextView(this).apply {
      text = getString(R.string.probe_drag_empty)
      setTextIsSelectable(true)
      typeface = Typeface.MONOSPACE
      textSize = 12f
    }
    root.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)

    zone.setOnDragListener { _, event -> onZoneDrag(event) }
    source.setOnLongClickListener { startSourceDrag(source) }
  }

  // --- Riceve (F168) --------------------------------------------------------------------------------------------------------

  private fun onZoneDrag(event: DragEvent): Boolean {
    when (event.action) {
      DragEvent.ACTION_DRAG_STARTED -> return true
      DragEvent.ACTION_DRAG_ENTERED -> zone.alpha = 1f
      DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> zone.alpha = IDLE_ALPHA
      DragEvent.ACTION_DROP -> {
        zone.alpha = IDLE_ALPHA
        onDrop(event)
      }
    }
    return true
  }

  private fun onDrop(event: DragEvent) {
    val n = ++drops
    val clip = event.clipData
    val description = event.clipDescription
    // Il permesso di lettura del trascinamento: senza, l'altra app non puo' leggere niente (non persistente: finisce con questa activity).
    val permissions = requestDragAndDropPermissions(event)
    val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
    add(
      "#$n rilascio: ${uris.size} indirizzi, tipi=${(0 until (description?.mimeTypeCount ?: 0)).joinToString(",") { description!!.getMimeType(it) }}, " +
        "permesso=${if (permissions != null) "si" else "no"}, stato locale=${if (event.localState == null) "assente (altra app)" else "presente"}",
    )
    Thread({
      for ((i, uri) in uris.withIndex()) add("#$n.${i + 1} ${measure(uri)}")
      runOnUiThread { permissions?.let { add("#$n permesso lasciato all'activity (finisce quando si chiude)") } }
    }, "field-probe-drag").start()
  }

  /** Una riga di soli numeri e forme per un indirizzo: si apre? quanti byte? la scrittura e' negata? un permesso persistente e' rifiutato? */
  private fun measure(uri: android.net.Uri): String {
    val started = SystemClock.elapsedRealtime()
    var bytes = 0L
    var sha = "-"
    var read = "ok"
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(BUFFER)
      (contentResolver.openInputStream(uri) ?: throw java.io.IOException("nessun flusso")).use { input ->
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          digest.update(buffer, 0, n)
          bytes += n
        }
      }
      sha = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    } catch (e: Exception) {
      read = "fallita(${e.javaClass.simpleName})"
    }
    val ms = SystemClock.elapsedRealtime() - started
    val write = try {
      contentResolver.openFileDescriptor(uri, "w")?.close()
      "CONSENTITA"
    } catch (e: Exception) {
      "negata"
    }
    val persistent = try {
      contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      "PRESA"
    } catch (e: Exception) {
      "rifiutata"
    }
    return "${uri.scheme}://${uri.authority} lettura=$read $bytes B in $ms ms sha=$sha scrittura=$write persistente=$persistent"
  }

  // --- Trascina (F169) --------------------------------------------------------------------------------------------------------

  /** Trascina fino a tre foto di `mcg-prova` verso un'altra app (o MyCloud Gallery): il gesto di un'app che trascina contenuti suoi, con la sola lettura sul singolo indirizzo. */
  private fun startSourceDrag(from: View): Boolean {
    val rows = try {
      FieldProbePhone(this).rows().take(MAX_SOURCE)
    } catch (e: Exception) {
      emptyList()
    }
    if (rows.isEmpty()) {
      add(getString(R.string.probe_drag_no_rows))
      return true
    }
    val phone = FieldProbePhone(this)
    val clip = ClipData(ClipDescription("", rows.map { it.mime }.distinct().toTypedArray()), ClipData.Item(phone.uriOf(rows.first())))
    rows.drop(1).forEach { clip.addItem(ClipData.Item(phone.uriOf(it))) }
    val started = from.startDragAndDrop(clip, View.DragShadowBuilder(from), null, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    add("trascinamento di prova: ${rows.size} foto, partito=$started")
    return true
  }

  // --- Dentro -----------------------------------------------------------------------------------------------------------------

  private fun add(line: String) {
    synchronized(lines) {
      lines += line
      try {
        File(File(noBackupFilesDir, "field-probe").also { it.mkdirs() }, "drag-report.txt").writeText(lines.joinToString("\n"))
      } catch (_: java.io.IOException) {
        // Il resoconto a video resta.
      }
    }
    runOnUiThread { synchronized(lines) { report.text = lines.joinToString("\n") } }
  }

  private fun tile(label: String): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    textSize = 16f
    minHeight = dp(120)
    setPadding(dp(12), dp(12), dp(12), dp(12))
    alpha = IDLE_ALPHA
    background = GradientDrawable().apply {
      setStroke(dp(2), Color.GRAY)
      cornerRadius = dp(12).toFloat()
    }
  }

  private fun tileParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private companion object {
    const val IDLE_ALPHA = 0.7f
    const val BUFFER = 256 * 1024
    const val MAX_SOURCE = 3
  }
}
