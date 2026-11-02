package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// LE DESTINAZIONI DEL TRASCINAMENTO IN ARRIVO (Fase 10, P6; docs/fase10-progetto.md §2.5 "Dentro l'app", D21, C437, E-T15, E-T16). Una foto trascinata da Archivio, dalla
// Galleria di Samsung o da un'altra app su **la Libreria**, su **un album** o su **una cartella di Cartelle NAS** non parte da sola: si apre il **foglio della
// ricezione** ("Salva sul NAS", "Aggiungi ad album...") con il conteggio, e da li' vale solo un tocco. Il design system fa solo il gesto e l'evidenziazione; cosa
// fare del rilascio lo decide la porta [MediaDropPort] (`:app` la lega al flusso di ricezione, `feature-integrations/.../drag/`).

/** Dove si e' rilasciato. Gli id e i percorsi sono stringhe: il design system non conosce i moduli dei dati. Mai in un log ([toString] non li scrive). */
sealed interface DropTarget {

  /** La Libreria: "Salva sul NAS". */
  data object Library : DropTarget

  /** Un album dove l'utente puo' aggiungere (E-T16: un album condiviso dove "guardano" soltanto non e' una destinazione): "Aggiungi ad album". [albumId] e' il valore dell'`AlbumId`. */
  data class Album(val albumId: String) : DropTarget {
    override fun toString(): String = "DropTarget.Album"
  }

  /** Una cartella di Cartelle NAS: "Salva sul NAS" in quella cartella. [share] e [path] sono dati dell'utente. */
  data class NasFolder(val share: String, val path: String) : DropTarget {
    override fun toString(): String = "DropTarget.NasFolder"
  }
}

/**
 * Un trascinamento in arrivo, come lo vede una destinazione: i tipi che l'altra app dichiara ([description]), i flussi ([clip], solo al rilascio), se e' partito da noi ([own]: lo
 * stato locale e' il nostro segno, [MediaDragLocalState]) e come chiedere il permesso di lettura ([grantAccess], `requestDragAndDropPermissions`). Valori semplici: il design system non
 * lascia uscire il tipo di Compose, e i test ne costruiscono uno senza il sistema che trascina.
 */
class IncomingDrag(val description: ClipDescription?, val clip: ClipData?, val own: Boolean, val grantAccess: () -> Unit = {}) {
  override fun toString(): String = "IncomingDrag(own=$own)"
}

/** La porta con cui una destinazione chiede cosa fare di un trascinamento in arrivo. */
@Stable
interface MediaDropPort {

  /**
   * Il trascinamento in corso puo' essere ricevuto su [target]? Economica e senza effetti: si chiama all'inizio di ogni trascinamento sullo schermo. **Un trascinamento
   * partito dall'app stessa non si offre** ([IncomingDrag.own]): un media nostro non si "riceve" da se' stesso.
   */
  fun accepts(target: DropTarget, drag: IncomingDrag): Boolean

  /**
   * Il rilascio su [target]. Chiede il permesso di lettura del trascinamento ([IncomingDrag.grantAccess]) e porta i flussi al **foglio della ricezione** ([activity] lo avvia): niente si
   * copia ne' si salva senza il suo tocco. Vero se il rilascio e' stato preso.
   */
  fun drop(target: DropTarget, drag: IncomingDrag, activity: Activity?): Boolean
}

/** La porta delle destinazioni del processo: `:app` la installa una volta all'avvio. */
object MediaDropPorts {
  @Volatile
  var installed: MediaDropPort? = null
    private set

  fun install(port: MediaDropPort?) {
    installed = port
  }

  fun reset() = install(null)
}

/** La porta delle destinazioni nella composizione (test, anteprime); `null` = vale quella del processo, e senza nemmeno quella nessuna destinazione si offre. */
val LocalMediaDropPort = staticCompositionLocalOf<MediaDropPort?> { null }

/** Dove sta un trascinamento rispetto a una destinazione: per l'evidenziazione. */
internal enum class DropPhase { Idle, Offered, Over }

/**
 * Fa di questa superficie una destinazione per i trascinamenti in arrivo. Mentre un trascinamento che [MediaDropPort.accepts] e' in corso la superficie si
 * evidenzia con un filo; quando ci passa sopra, di piu'. Con [enabled] falso o senza porta non fa niente (e non evidenzia niente).
 */
@Composable
fun Modifier.mediaDropTarget(target: DropTarget, enabled: Boolean = true): Modifier {
  val port = LocalMediaDropPort.current ?: MediaDropPorts.installed
  if (!enabled || port == null) return this
  val context = LocalContext.current
  val targetNow by rememberUpdatedState(target)
  var phase by remember { mutableStateOf(DropPhase.Idle) }
  val outline = MaterialTheme.colorScheme.primary
  val receiver = remember(port, context) {
    object : DragAndDropTarget {
      override fun onStarted(event: DragAndDropEvent) {
        phase = DropPhase.Offered
      }

      override fun onEntered(event: DragAndDropEvent) {
        phase = DropPhase.Over
      }

      override fun onExited(event: DragAndDropEvent) {
        phase = DropPhase.Offered
      }

      override fun onEnded(event: DragAndDropEvent) {
        phase = DropPhase.Idle
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        phase = DropPhase.Idle
        return port.drop(targetNow, event.toIncoming(context, withClip = true), context.findActivity())
      }
    }
  }
  return this
    .dragAndDropTarget(shouldStartDragAndDrop = { event -> port.accepts(targetNow, event.toIncoming(context, withClip = false)) }, target = receiver)
    .dropOutline({ phase }, outline)
}

/**
 * L'evidenziazione di una destinazione: un filo attorno alla superficie quando un trascinamento che si puo' ricevere e' in corso ([DropPhase.Offered]), piu' marcato quando ci passa sopra
 * ([DropPhase.Over]). La fase si legge nel disegno (non nella composizione): cambiarla ridisegna soltanto. Il colore e' quello del tema.
 */
internal fun Modifier.dropOutline(phase: () -> DropPhase, color: androidx.compose.ui.graphics.Color): Modifier = drawWithContent {
  drawContent()
  val current = phase()
  if (current == DropPhase.Idle) return@drawWithContent
  val width = (if (current == DropPhase.Over) OVER_STROKE else OFFERED_STROKE).toPx()
  val inset = width / 2
  drawRect(
    color = color.copy(alpha = if (current == DropPhase.Over) OVER_ALPHA else OFFERED_ALPHA),
    topLeft = Offset(inset, inset),
    size = Size(size.width - width, size.height - width),
    style = Stroke(width = width),
  )
}

/** Il trascinamento come lo vede la porta; i flussi ([withClip]) solo al rilascio (prima Android non li da'). */
private fun DragAndDropEvent.toIncoming(context: Context, withClip: Boolean): IncomingDrag {
  val android = toAndroidDragEvent()
  return IncomingDrag(
    description = android.clipDescription,
    clip = if (withClip) android.clipData else null,
    own = android.localState === MediaDragLocalState,
    grantAccess = { context.findActivity()?.requestDragAndDropPermissions(android) },
  )
}

/** L'activity che contiene [this], risalendo i `ContextWrapper`, o null (anteprima, test). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

private val OFFERED_STROKE = 2.dp
private val OVER_STROKE = 4.dp
private const val OFFERED_ALPHA = 0.45f
private const val OVER_ALPHA = 0.95f
