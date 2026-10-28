package dev.pampa.mycloudgallery.core.designsystem.instant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Dove le pagine ricordano la loro forma fra un avvio e l'altro (Avvio istantaneo, 28/10). Questo modulo non vede il file (sta in
 * core-common, `InstantShapes`): l'app lo lega all'avvio del processo ([install]); senza legame ogni pagina usa la forma di serie
 * (test, anteprime).
 */
object InstantShapeMemory {

  @Volatile private var read: (String) -> List<Int>? = { null }
  @Volatile private var write: (String, List<Int>) -> Unit = { _, _ -> }

  /** Lega la memoria al file delle forme: [reader] da' i numeri ricordati per un codice, [writer] li ricorda. */
  fun install(reader: (String) -> List<Int>?, writer: (String, List<Int>) -> Unit) {
    read = reader
    write = writer
  }

  /** La forma ricordata per [code], o null se non ce n'e' una valida (la pagina non e' mai stata vista con dei dati). */
  fun rememberedOrNull(code: String): InstantShape? = read(code)?.let(InstantShape::decode)?.takeIf { !it.isEmpty }

  /** La forma ricordata per [code], o [default]. */
  fun shapeOf(code: String, default: InstantShape): InstantShape = read(code)?.let(InstantShape::decode) ?: default

  /** Ricorda la forma di [code] (una forma vuota non si ricorda: al prossimo ingresso vale quella di serie). */
  fun remember(code: String, shape: InstantShape) {
    if (!shape.isEmpty) write(code, shape.encode())
  }
}

/** La forma con cui disegnare lo scheletro di [code] al primo ingresso: quella dell'ultima volta, o [default]. */
@Composable
fun rememberInstantShape(code: String, default: InstantShape): InstantShape = remember(code) { InstantShapeMemory.shapeOf(code, default) }

/** Ricorda [shape] per [code] quando la pagina mostra i dati veri (una scrittura accorpata, fuori dal thread principale). */
@Composable
fun KeepInstantShape(code: String, shape: InstantShape) {
  LaunchedEffect(code, shape) { InstantShapeMemory.remember(code, shape) }
}
