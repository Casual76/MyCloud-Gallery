package dev.pampa.mycloudgallery.feature.viewer

import java.io.ByteArrayOutputStream

/**
 * Le foto in movimento sintetiche dei test di P14 (Google v1, MicroVideo, trailer SEF di Samsung, HEIC non riconosciuto), scritte come le
 * fixture di core-metadata (`tools/fixtures`: MotionFixtures, XmpPackets), che questo modulo non puo' usare senza toccare la build.
 * La parita' con il localizzatore vero si prova confrontando il risultato con `MotionPhotoLocator` sugli stessi byte.
 *
 * Il JPEG e' minimo (SOI, un APP1 con l'XMP, EOI): al localizzatore basta il pacchetto XMP nei primi byte.
 */
internal object MotionTestFiles {

  private const val NS_CAMERA = "http://ns.google.com/photos/1.0/camera/"
  private const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
  private const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"

  /** Un MP4 di prova che comincia con `ftyp` e ha un `moov` con `mvhd` (timescale 1000): dura [durationMs]. */
  fun mp4(durationMs: Long = 3_000L, mdatBytes: Int = 3_000, moovLast: Boolean = false): ByteArray {
    val ftyp = box("ftyp", ascii("isom") + u32(0x200) + ascii("isom") + ascii("mp42"))
    val mvhdBody = ByteArrayOutputStream().apply {
      write(u32(0)) // versione e flag
      write(u32(0)) // creazione
      write(u32(0)) // modifica
      write(u32(1000)) // timescale
      write(u32(durationMs)) // durata
      write(ByteArray(80)) // il resto del box
    }.toByteArray()
    val moov = box("moov", box("mvhd", mvhdBody))
    val mdat = box("mdat", ByteArray(mdatBytes))
    return if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat
  }

  /** Un MP4 senza `moov`: la durata non si legge. */
  fun mp4WithoutDuration(mdatBytes: Int = 3_000): ByteArray = box("ftyp", ascii("isom") + u32(0x200) + ascii("isom")) + box("mdat", ByteArray(mdatBytes))

  fun jpeg(xmp: String?): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    if (xmp != null) {
      val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
      out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
      out.write(byteArrayOf(((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()))
      out.write(payload)
    }
    out.write(ByteArray(64) { 0x11 })
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  /** Google v1 come lo scrive un Pixel; [declaredUs] null = senza il campo, -1 = "non impostato". */
  fun google(video: ByteArray, declaredUs: Long?): ByteArray = googleHead(video.size.toLong(), declaredUs) + video

  /** Solo la parte JPEG di un Google v1 che dichiara un video lungo [videoLength]: per i file "grandi" con il corpo virtuale. */
  fun googleHead(videoLength: Long, declaredUs: Long?): ByteArray {
    val timestamp = declaredUs?.let { " GCamera:MotionPhotoPresentationTimestampUs=\"$it\"" }.orEmpty()
    val ns = "xmlns:GCamera=\"$NS_CAMERA\" xmlns:Container=\"$NS_CONTAINER\" xmlns:Item=\"$NS_ITEM\""
    val inner = "   <Container:Directory><rdf:Seq>\n" +
      "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>\n" +
      "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/></rdf:li>\n" +
      "   </rdf:Seq></Container:Directory>\n"
    return jpeg(wrap(ns, "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"$timestamp", inner))
  }

  /** MicroVideo: `MicroVideoOffset` dalla fine del file; [declaredUs] null = senza il campo. */
  fun microVideo(video: ByteArray, declaredUs: Long?): ByteArray {
    val timestamp = declaredUs?.let { " GCamera:MicroVideoPresentationTimestampUs=\"$it\"" }.orEmpty()
    val attrs = "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"${video.size}\"$timestamp"
    return jpeg(wrap("xmlns:GCamera=\"$NS_CAMERA\"", attrs, "")) + video
  }

  /**
   * Samsung: il video e' il valore del blocco 0x0A30 "MotionPhoto_Data" nel trailer SEF. [declaredUs] non null = l'XMP porta anche
   * l'istante (facoltativo nei file Samsung); null = nessun XMP, come i file dell'S25.
   */
  fun samsungSef(video: ByteArray, declaredUs: Long?): ByteArray {
    val xmp = declaredUs?.let { wrap("xmlns:GCamera=\"$NS_CAMERA\"", "GCamera:MotionPhotoPresentationTimestampUs=\"$it\"", "") }
    val jpeg = jpeg(xmp)
    val blocks = listOf(
      Triple(0x0A01, "Image_UTC_Data", "1710238530000".toByteArray()),
      Triple(0x0A30, "MotionPhoto_Data", video),
      Triple(0x0A31, "MotionPhoto_Version", "1".toByteArray()),
    )
    var cursor = jpeg.size.toLong()
    val body = ByteArrayOutputStream()
    val placed = ArrayList<Pair<Long, Int>>()
    for ((type, name, value) in blocks) {
      val bytes = u16le(0) + u16le(type) + u32le(name.length.toLong()) + ascii(name) + value
      placed += cursor to bytes.size
      body.write(bytes)
      cursor += bytes.size
    }
    val directoryStart = cursor
    val directory = ByteArrayOutputStream().apply {
      write(ascii("SEFH") + u32le(101) + u32le(blocks.size.toLong()))
      blocks.forEachIndexed { i, (type, _, _) ->
        write(u16le(0) + u16le(type) + u32le(directoryStart - placed[i].first) + u32le(placed[i].second.toLong()))
      }
    }.toByteArray()
    return jpeg + body.toByteArray() + directory + u32le(directory.size.toLong()) + ascii("SEFT")
  }

  /** Un HEIC di Samsung: nessun localizzatore lo riconosce (E-M16). */
  fun heicSamsung(): ByteArray = box("ftyp", ascii("heic") + u32(0) + ascii("mif1") + ascii("heic")) + ByteArray(2_000) { 0x22 }

  private fun wrap(namespaces: String, attributes: String, inner: String): String =
    "<?xpacket begin=\"\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
      "  <rdf:Description rdf:about=\"\" $namespaces $attributes>\n$inner  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>"

  private fun box(type: String, content: ByteArray): ByteArray = u32(8L + content.size) + ascii(type) + content

  private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

  private fun u32(value: Long): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

  private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

  private fun u32le(value: Long): ByteArray = u32(value).reversedArray()

  private fun u16le(value: Int): ByteArray = u16(value).reversedArray()
}
