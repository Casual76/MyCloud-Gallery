package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * Fase 8 (P2): la FORMA dei due file `.tflite` che stanno nell'APK, letta dai byte (un lettore minimo di FlatBuffers, senza interprete), contro
 * cio' che le sessioni di LiteRT danno per scontato (`FaceLiteRtSessions`): ingresso, dodici uscite di YuNet con i loro nomi e nell'ordine di
 * `YuNetLayout.OUTPUT_NAMES`, uscita di SFace, tutto float32 in ingresso e in uscita, solo operatori di serie (nessun operatore personalizzato:
 * gira sull'interprete di serie di `litert` 2.3.0). Se qualcuno sostituisce un modello con un altro, qui si vede sulla JVM e non sul telefono.
 */
class FaceModelContractTest {

  private val assets = File("src/main/assets")

  private fun read(spec: ModelSpec): TfliteModel = TfliteModel(File(assets, spec.bundledAsset!!).readBytes())

  @Test
  fun yuNetHaUnIngressoDa1024ELeDodiciUsciteNellOrdineEConLeFormeAttese() {
    val model = read(ModelCatalog.FACE_YUNET)
    assertThat(model.subgraphs).isEqualTo(1)
    val inputs = model.inputs()
    assertThat(inputs).hasSize(1)
    assertThat(inputs.single().shape).isEqualTo(listOf(1, YuNetLayout.INPUT_SIDE, YuNetLayout.INPUT_SIDE, 3))
    assertThat(inputs.single().type).isEqualTo(TfliteModel.FLOAT32)
    val outputs = model.outputs()
    assertThat(outputs.map { it.name }).containsExactlyElementsIn(YuNetLayout.OUTPUT_NAMES).inOrder()
    for ((i, tensor) in outputs.withIndex()) {
      assertWithMessage(tensor.name).that(tensor.shape).isEqualTo(YuNetLayout.expectedShape(i).toList())
      assertWithMessage(tensor.name).that(tensor.type).isEqualTo(TfliteModel.FLOAT32)
    }
  }

  @Test
  fun sFaceHaIngresso112x112x3EUnaUscitaDa128Float32() {
    val model = read(ModelCatalog.FACE_SFACE)
    assertThat(model.subgraphs).isEqualTo(1)
    val side = FaceEngineLimits.ALIGNED_SIDE
    val inputs = model.inputs()
    assertThat(inputs).hasSize(1)
    assertThat(inputs.single().shape).isEqualTo(listOf(1, side, side, 3))
    assertThat(inputs.single().type).isEqualTo(TfliteModel.FLOAT32)
    val outputs = model.outputs()
    assertThat(outputs).hasSize(1)
    assertThat(outputs.single().shape).isEqualTo(listOf(1, FaceSpaceCode.CURRENT.dimension))
    assertThat(outputs.single().type).isEqualTo(TfliteModel.FLOAT32)
    // I pesi sono in mezza precisione (D7) e il calcolo resta in float32: tensori float16 nel file e operatori DEQUANTIZE.
    assertThat(model.tensorCount(TfliteModel.FLOAT16)).isGreaterThan(50)
    assertThat(model.operatorCodes()).contains(TfliteModel.DEQUANTIZE)
  }

  @Test
  fun soloOperatoriDiSerie_NessunoPersonalizzato() {
    for (spec in ModelCatalog.FACES) {
      val model = read(spec)
      assertWithMessage(spec.fileName).that(model.customOperators()).isEmpty()
      val unknown = model.operatorCodes().filter { it !in ALLOWED_OPERATORS }
      assertWithMessage("${spec.fileName}: operatori fuori dall'elenco di P1").that(unknown).isEmpty()
    }
    // YuNet: le convoluzioni, i pool, l'ingrandimento a vicino piu' prossimo e le sigmoidi; niente di piu'.
    val yunet = read(ModelCatalog.FACE_YUNET).operatorCodes()
    assertThat(yunet).containsAtLeast(TfliteModel.CONV_2D, TfliteModel.DEPTHWISE_CONV_2D, TfliteModel.MAX_POOL_2D, TfliteModel.RESIZE_NEAREST_NEIGHBOR, TfliteModel.LOGISTIC)
  }

  @Test
  fun leDimensioniDeiTensoriSonoCoerentiConLeTaglieDelleSessioni() {
    // 21.504 celle in tutto (128^2 + 64^2 + 32^2) e 16 canali per cella: la matrice che YuNetOutputs si aspetta.
    val cells = YuNetLayout.CELLS.sumOf { it * it }
    assertThat(cells).isEqualTo(21_504)
    var values = 0
    for (i in 0 until 12) values += YuNetLayout.expectedShape(i).drop(1).fold(1) { a, b -> a * b }
    assertThat(values).isEqualTo(cells * (1 + 1 + 4 + 10))
  }

  private companion object {
    /** Gli operatori che P1 ha contato nei due file (docs/decisioni.md, "Fase 8 P1"). */
    val ALLOWED_OPERATORS: Set<Int> = setOf(
      TfliteModel.ADD, TfliteModel.CONV_2D, TfliteModel.DEPTHWISE_CONV_2D, TfliteModel.DEQUANTIZE, TfliteModel.FULLY_CONNECTED,
      TfliteModel.LOGISTIC, TfliteModel.MAX_POOL_2D, TfliteModel.MUL, TfliteModel.RESHAPE, TfliteModel.PAD, TfliteModel.TRANSPOSE,
      TfliteModel.SUB, TfliteModel.PRELU, TfliteModel.RESIZE_NEAREST_NEIGHBOR,
    )
  }
}

/** Un lettore minimo di un file `.tflite` (FlatBuffers, schema TFLite): tensori, ingressi, uscite e operatori. Solo per le prove. */
internal class TfliteModel(bytes: ByteArray) {

  class Tensor(val name: String, val shape: List<Int>, val type: Int)

  private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  private val root: Int

  init {
    require(bytes.size > 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "TFL3") { "Non e' un file TFLite." }
    root = u32(0)
  }

  private fun u8(p: Int) = b.get(p).toInt() and 0xff

  private fun u16(p: Int) = b.getShort(p).toInt() and 0xffff

  private fun i32(p: Int) = b.getInt(p)

  private fun u32(p: Int) = b.getInt(p)

  /** La posizione assoluta del campo [index] della tabella in [table], o -1 se manca. */
  private fun field(table: Int, index: Int): Int {
    val vtable = table - i32(table)
    val size = u16(vtable)
    val slot = 4 + 2 * index
    if (slot + 2 > size) return -1
    val offset = u16(vtable + slot)
    return if (offset == 0) -1 else table + offset
  }

  /** (inizio degli elementi, lunghezza) del vettore il cui offset sta in [at]. */
  private fun vector(at: Int): Pair<Int, Int> {
    val v = at + u32(at)
    return (v + 4) to u32(v)
  }

  private fun tableAt(vectorStart: Int, i: Int): Int {
    val p = vectorStart + 4 * i
    return p + u32(p)
  }

  private fun string(at: Int): String {
    val s = at + u32(at)
    val len = u32(s)
    return String(b.array(), s + 4, len, Charsets.UTF_8)
  }

  private val subgraphTables: List<Int> by lazy {
    val (start, count) = vector(field(root, 2))
    List(count) { tableAt(start, it) }
  }

  val subgraphs: Int get() = subgraphTables.size

  private fun tensorTables(): List<Int> {
    val (start, count) = vector(field(subgraphTables[0], 0))
    return List(count) { tableAt(start, it) }
  }

  private fun tensor(table: Int): Tensor {
    val shapeField = field(table, 0)
    val shape = if (shapeField < 0) emptyList() else vector(shapeField).let { (s, n) -> List(n) { i32(s + 4 * it) } }
    val typeField = field(table, 1)
    val nameField = field(table, 3)
    return Tensor(if (nameField < 0) "" else string(nameField), shape, if (typeField < 0) 0 else u8(typeField))
  }

  private fun indexed(subgraphField: Int): List<Tensor> {
    val tables = tensorTables()
    val (start, count) = vector(field(subgraphTables[0], subgraphField))
    return List(count) { tensor(tables[i32(start + 4 * it)]) }
  }

  fun inputs(): List<Tensor> = indexed(1)

  fun outputs(): List<Tensor> = indexed(2)

  fun tensorCount(type: Int): Int = tensorTables().count { tensor(it).type == type }

  /** Il codice di ogni operatore usato, nell'ordine del grafo (con i doppi). */
  fun operatorCodes(): List<Int> {
    val (codesStart, codesCount) = vector(field(root, 1))
    val codes = List(codesCount) { code(tableAt(codesStart, it)) }
    val (opsStart, opsCount) = vector(field(subgraphTables[0], 3))
    // opcode_index vale 0 di serie e allora il campo non e' scritto.
    return List(opsCount) { codes[field(tableAt(opsStart, it), 0).let { f -> if (f < 0) 0 else u32(f) }] }
  }

  /** Gli operatori personalizzati (con un `custom_code`). */
  fun customOperators(): List<String> {
    val (codesStart, codesCount) = vector(field(root, 1))
    return (0 until codesCount).mapNotNull { i ->
      val t = tableAt(codesStart, i)
      val f = field(t, 1)
      if (f < 0) null else string(f).takeIf { it.isNotEmpty() }
    }
  }

  private fun code(table: Int): Int {
    val deprecated = field(table, 0).let { if (it < 0) 0 else b.get(it).toInt() }
    val extended = field(table, 3).let { if (it < 0) 0 else i32(it) }
    return maxOf(deprecated, extended)
  }

  companion object {
    const val FLOAT32 = 0
    const val FLOAT16 = 1
    const val ADD = 0
    const val CONV_2D = 3
    const val DEPTHWISE_CONV_2D = 4
    const val DEQUANTIZE = 6
    const val FULLY_CONNECTED = 9
    const val LOGISTIC = 14
    const val MAX_POOL_2D = 17
    const val MUL = 18
    const val RESHAPE = 22
    const val PAD = 34
    const val TRANSPOSE = 39
    const val SUB = 41
    const val PRELU = 54
    const val RESIZE_NEAREST_NEIGHBOR = 97
  }
}
