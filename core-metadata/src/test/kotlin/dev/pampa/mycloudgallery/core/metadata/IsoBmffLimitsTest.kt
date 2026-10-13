package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.ImageScan
import dev.pampa.mycloudgallery.core.metadata.format.IsoBmff
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R1 della revisione di sicurezza della Fase 3 (docs/review/3-fase3.md): i tetti sui conteggi dichiarati dentro `meta`.
 * Per ogni conteggio che il lettore limita c'e' una prova "appena sotto il tetto passa, appena sopra il file e' non
 * leggibile (`malformed`)" e, sempre, nessuna eccezione e nessuna allocazione proporzionale al conteggio dichiarato.
 *
 * Il discriminante e' la larghezza: un HEIF costruito qui e' leggibile (ha `ispe` 64x48 associato alla voce primaria), quindi
 * `width == 64` vuol dire "il riquadro e' stato accettato" e `malformed` vuol dire "oltre il tetto".
 */
class IsoBmffLimitsTest {

  // ---- Costruttori di byte ------------------------------------------------------------------------------------------

  private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

  private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

  private fun ByteArrayOutputStream.u16(v: Int) {
    u8(v shr 8); u8(v)
  }

  private fun ByteArrayOutputStream.u32(v: Long) {
    u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt())
  }

  private fun ByteArrayOutputStream.u64(v: Long) {
    u32(v ushr 32); u32(v and 0xFFFFFFFFL)
  }

  private fun ByteArrayOutputStream.ascii(s: String) = s.forEach { u8(it.code) }

  private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray = bytes {
    u32((payload.size + 8).toLong())
    ascii(type)
    write(payload)
  }

  private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray = box(type, bytes { u8(version); u8(0); u8(0); u8(0); write(payload) })

  // ---- Un HEIF leggibile, con le parti sostituibili -----------------------------------------------------------------

  private fun infe(id: Int, type: String = "hvc1"): ByteArray = fullBox("infe", 2, bytes { u16(id); u16(0); ascii(type); u8(0) })

  /** `iinf` con le voci date (il conteggio dichiarato e' quello vero). */
  private fun iinf(entries: List<ByteArray>): ByteArray = fullBox("iinf", 0, bytes { u16(entries.size); entries.forEach { write(it) } })

  private val defaultIinf: ByteArray get() = iinf(listOf(infe(1)))

  /** `iloc` versione 1 con una voce che ha [extents] extent e le taglie date. */
  private fun iloc(
    items: List<IlocItem>,
    declaredCount: Int = items.size,
    offsetSize: Int = 4,
    lengthSize: Int = 4,
  ): ByteArray = fullBox(
    "iloc", 1,
    bytes {
      u8((offsetSize shl 4) or lengthSize)
      u8(0) // base_offset_size = 0, index_size = 0
      u16(declaredCount)
      for (item in items) {
        u16(item.id); u16(0); u16(0) // item_ID, construction_method 0, data_reference_index
        u16(item.extentCount)
        repeat(item.written) {
          for (k in 0 until offsetSize) u8(0)
          for (k in 0 until lengthSize) u8(0)
        }
      }
    },
  )

  /** Una voce dell'iloc: [extentCount] dichiarati, [written] davvero presenti nei byte. */
  private class IlocItem(val id: Int, val extentCount: Int, val written: Int = extentCount)

  private val defaultIloc: ByteArray get() = iloc(listOf(IlocItem(1, 1)))

  private fun ispe(): ByteArray = fullBox("ispe", 0, bytes { u32(64); u32(48) })

  /** `ipma` versione 0 con [declared] voci dichiarate e una sola presente: la voce 1 con l'associazione alla proprieta' 1. */
  private fun ipma(declared: Long = 1): ByteArray = fullBox("ipma", 0, bytes { u32(declared); u16(1); u8(1); u8(1) })

  private fun iprp(ipco: ByteArray = box("ipco", ispe()), ipma: ByteArray = ipma()): ByteArray = box("iprp", ipco + ipma)

  private fun heif(
    iinf: ByteArray = defaultIinf,
    iloc: ByteArray = defaultIloc,
    iprp: ByteArray = iprp(),
    extraMetaChildren: ByteArray = ByteArray(0),
  ): ByteArray {
    val ftyp = box("ftyp", bytes { ascii("heic"); u32(0); ascii("mif1"); ascii("heic") })
    val meta = fullBox("meta", 0, fullBox("pitm", 0, bytes { u16(1) }) + iinf + iloc + iprp + extraMetaChildren)
    return ftyp + meta
  }

  private suspend fun scan(file: ByteArray): ImageScan = IsoBmff.scanHeif(ArrayAccess(file))

  private fun assertReadable(scan: ImageScan) {
    assertThat(scan.malformed).isFalse()
    assertThat(scan.width).isEqualTo(64)
    assertThat(scan.height).isEqualTo(48)
  }

  private fun assertTooBig(scan: ImageScan) {
    assertThat(scan.malformed).isTrue()
    assertThat(scan.width).isNull()
  }

  @Test
  fun unHeifBenFattoSiLegge() = runTest {
    assertReadable(scan(heif()))
  }

  // ---- iloc: voci, extent per voce, extent in totale ------------------------------------------------------------------

  @Test
  fun ilocConPiuVociDelTettoNonSiLegge() = runTest {
    // item_count dichiarato oltre il tetto, anche se le voci vere sono una sola: il conteggio dichiarato non decide il lavoro.
    assertTooBig(scan(heif(iloc = iloc(listOf(IlocItem(1, 1)), declaredCount = IsoBmff.MAX_ITEMS + 1))))
    // Versione 2: item_count a 32 bit, il massimo possibile.
    val v2 = fullBox(
      "iloc", 2,
      bytes { u8(0x44); u8(0); u32(0xFFFFFFFFL); u32(1); u16(0); u16(0); u16(1); u32(0); u32(0) },
    )
    assertTooBig(scan(heif(iloc = v2)))
  }

  @Test
  fun ilocAlTettoDelleVociSiLegge() = runTest {
    val items = (1..IsoBmff.MAX_ITEMS).map { IlocItem(it, 1) }
    assertReadable(scan(heif(iloc = iloc(items))))
  }

  @Test
  fun ilocConPiuExtentPerVoceDelTettoNonSiLegge() = runTest {
    // Taglie a zero: gli extent non consumano byte, quindi il solo conteggio decide l'allocazione (65535 e' il massimo di un u16).
    assertTooBig(scan(heif(iloc = iloc(listOf(IlocItem(1, 65_535, written = 65_535)), offsetSize = 0, lengthSize = 0))))
    assertTooBig(scan(heif(iloc = iloc(listOf(IlocItem(1, IsoBmff.MAX_EXTENTS_PER_ITEM + 1)), offsetSize = 0, lengthSize = 0))))
    // Anche con le taglie vere e i byte presenti.
    assertTooBig(scan(heif(iloc = iloc(listOf(IlocItem(1, IsoBmff.MAX_EXTENTS_PER_ITEM + 1))))))
  }

  @Test
  fun ilocAlTettoDegliExtentPerVoceSiLegge() = runTest {
    assertReadable(scan(heif(iloc = iloc(listOf(IlocItem(1, IsoBmff.MAX_EXTENTS_PER_ITEM)), offsetSize = 0, lengthSize = 0))))
    assertReadable(scan(heif(iloc = iloc(listOf(IlocItem(1, IsoBmff.MAX_EXTENTS_PER_ITEM))))))
  }

  @Test
  fun ilocConPiuExtentInTuttoDelTettoNonSiLegge() = runTest {
    val perItem = IsoBmff.MAX_EXTENTS_PER_ITEM
    val itemsAtCap = IsoBmff.MAX_EXTENTS_TOTAL / perItem
    // Ogni voce e' sotto il tetto per voce, e anche il numero di voci: e' il totale a sforare.
    val atCap = (1..itemsAtCap).map { IlocItem(it, perItem) }
    assertReadable(scan(heif(iloc = iloc(atCap, offsetSize = 0, lengthSize = 0))))
    val overCap = (1..itemsAtCap + 1).map { IlocItem(it, perItem) }
    assertTooBig(scan(heif(iloc = iloc(overCap, offsetSize = 0, lengthSize = 0))))
  }

  @Test
  fun ilocConExtentChePiuDeiByteRimastiNonSiLegge() = runTest {
    // Taglie a 8 byte e 32 extent dichiarati, ma nel riquadro ce n'e' uno solo: i byte residui non bastano.
    val short = iloc(listOf(IlocItem(1, extentCount = IsoBmff.MAX_EXTENTS_PER_ITEM, written = 1)), offsetSize = 8, lengthSize = 8)
    assertTooBig(scan(heif(iloc = short)))
    // Una voce tagliata prima di finire l'intestazione.
    val cut = fullBox("iloc", 1, bytes { u8(0x44); u8(0); u16(1); u16(1); u16(0) })
    assertTooBig(scan(heif(iloc = cut)))
  }

  @Test
  fun ilocConPosizioniFuoriScalaNonSiLegge() = runTest {
    // Offset a 64 bit con il bit alto acceso: negativo come Long, non e' una posizione.
    val negative = fullBox(
      "iloc", 1,
      bytes { u8(0x88); u8(0); u16(1); u16(1); u16(0); u16(0); u16(1); u64(-1L); u64(16) },
    )
    assertTooBig(scan(heif(iloc = negative)))
    // Una lunghezza negativa.
    val negativeLength = fullBox(
      "iloc", 1,
      bytes { u8(0x88); u8(0); u16(1); u16(1); u16(0); u16(0); u16(1); u64(0); u64(Long.MIN_VALUE) },
    )
    assertTooBig(scan(heif(iloc = negativeLength)))
  }

  @Test
  fun unExtentCheEsceDalFileNonLanciaENonLeggeNiente() = runTest {
    // Una voce Exif con offset enorme (ma non negativo) e lunghezza piccola: la somma non deve traboccare.
    val iinf = iinf(listOf(infe(1), infe(2, "Exif")))
    val iloc = fullBox(
      "iloc", 1,
      bytes {
        u8(0x88); u8(0); u16(2)
        u16(1); u16(0); u16(0); u16(1); u64(0); u64(16)
        u16(2); u16(0); u16(0); u16(1); u64(Long.MAX_VALUE - 2); u64(16)
      },
    )
    val scan = scan(heif(iinf = iinf, iloc = iloc))
    assertThat(scan.exifTiff).isNull()
    assertThat(scan.width).isEqualTo(64)
  }

  // ---- iinf, ipma, ipco, figli di meta -------------------------------------------------------------------------------

  @Test
  fun iinfConPiuVociDelTettoNonSiLegge() = runTest {
    val atCap = iinf((1..IsoBmff.MAX_ITEMS).map { infe(it) })
    assertReadable(scan(heif(iinf = atCap)))
    val overCap = iinf((1..IsoBmff.MAX_ITEMS + 1).map { infe(it) })
    assertTooBig(scan(heif(iinf = overCap)))
  }

  @Test
  fun ipmaConPiuAssociazioniDelTettoNonSiLegge() = runTest {
    // Il conteggio dichiarato (32 bit) e' enorme, le voci vere una: va rifiutato prima di camminarci sopra.
    assertTooBig(scan(heif(iprp = iprp(ipma = ipma(declared = 0xFFFFFFFFL)))))
    assertTooBig(scan(heif(iprp = iprp(ipma = ipma(declared = IsoBmff.MAX_ITEMS + 1L)))))
    assertReadable(scan(heif(iprp = iprp(ipma = ipma(declared = IsoBmff.MAX_ITEMS.toLong())))))
  }

  @Test
  fun ipcoConPiuProprietaDelTettoNonSiLegge() = runTest {
    val free = box("free")
    fun ipcoWith(properties: Int): ByteArray = box("ipco", ispe() + bytes { repeat(properties - 1) { write(free) } })
    // La prima proprieta' e' ispe (indice 1): in tutto, fino al tetto compreso.
    assertReadable(scan(heif(iprp = iprp(ipco = ipcoWith(IsoBmff.MAX_PROPERTIES)))))
    assertTooBig(scan(heif(iprp = iprp(ipco = ipcoWith(IsoBmff.MAX_PROPERTIES + 1)))))
  }

  @Test
  fun metaConPiuRiquadriFigliDelTettoNonSiLegge() = runTest {
    val free = box("free")
    val many = bytes { repeat(IsoBmff.MAX_CHILD_BOXES + 1) { write(free) } }
    assertTooBig(scan(heif(extraMetaChildren = many)))
    // Pochi riquadri in piu' di quelli veri: bene.
    val few = bytes { repeat(16) { write(free) } }
    assertReadable(scan(heif(extraMetaChildren = few)))
  }

  // ---- Fino all'estrattore: foto non leggibile, mai un'eccezione -------------------------------------------------------

  @Test
  fun l_estrattoreDaCorruptSuUnHeifOstileEnonLancia() = runTest {
    val hostile = heif(iloc = iloc(listOf(IlocItem(1, 65_535, written = 65_535)), offsetSize = 0, lengthSize = 0))
    val hints = ExtractHints("image/heic", "ostile.heic", "DCIM/Camera/", null, null, null, null, null, ROME)
    val result = testExtractor().extract(ByteArrayMediaBytes(hostile), hints)
    assertThat(result).isEqualTo(Extraction.Corrupt(ExtractCode.DECODE_FAILED))
  }

  // ---- ftyp: marche compatibili -----------------------------------------------------------------------------------------

  /** Un `ftyp` con la marca principale `isom` e queste marche compatibili, nell'ordine dato. */
  private fun ftypWith(compatible: List<String>): ByteArray = box("ftyp", bytes { ascii("isom"); u32(0); compatible.forEach { ascii(it) } }) + ByteArray(8)

  @Test
  fun ftypLeggeSoloLeMarcheCompatibiliFinoAlTetto() {
    val filler = List(MAX_BRANDS) { "iso2" }
    // L'ultima marca entro il tetto e' letta: il file e' un'immagine HEIF.
    val inside = ftypWith(filler.dropLast(1) + "heic")
    assertThat(Sniffer.sniff(inside, null).type).isEqualTo(ContainerType.HEIF)
    // Una marca d'immagine appena oltre il tetto non conta: resta un video MP4.
    val outside = ftypWith(filler + "heic")
    assertThat(Sniffer.sniff(outside, null).type).isEqualTo(ContainerType.MP4)
    // Un ftyp con migliaia di marche (testa lunga) finisce subito e non si perde: nessuna eccezione, nessun HEIF.
    val thousands = ftypWith(List(10_000) { "iso2" } + "heic")
    assertThat(Sniffer.sniff(thousands, null).type).isEqualTo(ContainerType.MP4)
  }

  private companion object {
    const val MAX_BRANDS: Int = Sniffer.MAX_COMPATIBLE_BRANDS
  }
}
