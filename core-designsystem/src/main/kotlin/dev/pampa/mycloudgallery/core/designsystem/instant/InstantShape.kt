package dev.pampa.mycloudgallery.core.designsystem.instant

import androidx.compose.runtime.Immutable

/**
 * La **forma** di una pagina (Avvio istantaneo, 28/10): quanti blocchi, quante righe, quante piastrelle. Serve a disegnare al
 * primo ingresso uno scheletro **con le dimensioni giuste** al posto di una rotella, cosi' quando arrivano i dati veri niente
 * salta. Solo numeri: mai un titolo, un nome, un id o un media (non c'e' niente da nascondere, ne' da togliere quando un media
 * si nasconde).
 */
@Immutable
data class InstantShape(val blocks: List<InstantBlock>) {

  /** Vero se non c'e' niente da disegnare. */
  val isEmpty: Boolean get() = blocks.isEmpty()

  /** La forma in numeri, per il file delle forme ([InstantShapes]): un tipo e due numeri per blocco. */
  fun encode(): List<Int> = blocks.flatMap { block ->
    when (block) {
      is InstantBlock.Header -> listOf(KIND_HEADER, 0, 0)
      is InstantBlock.Rows -> listOf(KIND_ROWS, block.count, 0)
      is InstantBlock.Tiles -> listOf(KIND_TILES, block.count, block.columns)
    }
  }

  companion object {
    /** Il massimo di blocchi e di elementi per blocco che si ricordano: uno scheletro non deve costare piu' della pagina. */
    const val MAX_BLOCKS: Int = 24
    const val MAX_ITEMS: Int = 60

    val EMPTY: InstantShape = InstantShape(emptyList())

    private const val KIND_HEADER = 1
    private const val KIND_ROWS = 2
    private const val KIND_TILES = 3

    /** La forma da [encode]; null se i numeri non sono una forma (file vecchio o rovinato: si usa quella di serie). */
    fun decode(values: List<Int>): InstantShape? {
      if (values.size % 3 != 0 || values.size / 3 > MAX_BLOCKS) return null
      val blocks = ArrayList<InstantBlock>(values.size / 3)
      for (i in values.indices step 3) {
        val a = values[i + 1]
        val b = values[i + 2]
        if (a < 0 || b < 0 || a > MAX_ITEMS || b > MAX_ITEMS) return null
        blocks += when (values[i]) {
          KIND_HEADER -> InstantBlock.Header
          KIND_ROWS -> if (a > 0) InstantBlock.Rows(a) else return null
          KIND_TILES -> if (a > 0 && b > 0) InstantBlock.Tiles(a, b) else return null
          else -> return null
        }
      }
      return InstantShape(blocks)
    }

    /** Una forma con i blocchi dati, tagliata ai massimi. */
    fun of(vararg blocks: InstantBlock): InstantShape = InstantShape(blocks.take(MAX_BLOCKS).map { it.clamped() })
  }
}

/** Un blocco della forma: un'intestazione di sezione, un gruppo di righe, una griglia di piastrelle. */
@Immutable
sealed interface InstantBlock {

  /** Un'intestazione di sezione (una riga corta di testo). */
  data object Header : InstantBlock

  /** Un gruppo di [count] righe (una `FluidListGroup`). */
  data class Rows(val count: Int) : InstantBlock

  /** Una griglia di [count] piastrelle su [columns] colonne (copertine, volti, celle). */
  data class Tiles(val count: Int, val columns: Int) : InstantBlock

  /** Il blocco entro i massimi di [InstantShape]. */
  fun clamped(): InstantBlock = when (this) {
    Header -> this
    is Rows -> Rows(count.coerceIn(1, InstantShape.MAX_ITEMS))
    is Tiles -> Tiles(count.coerceIn(1, InstantShape.MAX_ITEMS), columns.coerceIn(1, InstantShape.MAX_ITEMS))
  }
}
