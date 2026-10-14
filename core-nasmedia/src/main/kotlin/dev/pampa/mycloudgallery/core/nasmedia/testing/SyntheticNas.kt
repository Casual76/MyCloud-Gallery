package dev.pampa.mycloudgallery.core.nasmedia.testing

import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.random.Random

/**
 * Il NAS **sintetico** (docs/fase4-progetto.md §5.3): file virtuali (dimensione + seme, contenuto generato a richiesta per
 * le letture a intervalli) con le proporzioni dell'inventario (`docs/inventario-nas.md`, §4-bis), sopra il NAS finto
 * [InMemoryNasMedia]. Deterministico per [seed].
 *
 * Ogni file virtuale ha davanti un'intestazione plausibile (JPEG `FF D8 FF E1`, PNG, MP4 `ftyp`, Matroska) e un corpo
 * pseudo-casuale: [HeadSniffingExtractor] li riconosce come media, mentre un `.jpg` "finto" (E-M12) ha solo byte casuali.
 * I file **veri** piccoli (JPEG con EXIF e miniatura, HEIC, WebP animati, MP4 con indice in testa e in coda) li passano i
 * test con [realContent], dalle fixture di `tools/fixtures` (P6) o scritti a mano.
 *
 * @property realContent se restituisce dei byte per un percorso, il file usa quelli invece del contenuto virtuale.
 */
class SyntheticNas(
  val nas: InMemoryNasMedia,
  val seed: Long = 1,
  private val realContent: (path: String) -> ByteArray? = { null },
) {

  /**
   * Le proporzioni da generare.
   *
   * [INVENTARIO] riproduce `docs/inventario-nas.md` §4-bis: la share privata (`alessio`) e' piatta, 12.283 file in 7
   * cartelle con una da 8.910; la comune (`NAS`) ha 2.356 cartelle, quasi tutte album di musica con 2,4 copertine da
   * ~40 KB l'una (5.285 JPG in tutto), ~13 mila tracce, 371 MP4 e 9 MKV (fino a 13,5 GB, virtuali).
   */
  enum class Profile(
    /** Immagini nella share privata (JPG, PNG, HEIC, GIF, WebP, DNG). */
    val privateImages: Int,
    val privateVideos: Int,
    /** Una cartella della share privata con questi file (8.910 in `alessio`, E-N5). */
    val bigFolder: Int,
    /** Album di musica nella share comune (cartelle `Musica/Artista n/Album m`, 10 album per artista). */
    val albums: Int,
    val tracksPerAlbum: Int,
    val commonPhotos: Int,
    val commonVideos: Int,
  ) {
    /** Le proporzioni dell'inventario (~12 mila file privati, ~20 mila nella comune). */
    INVENTARIO(privateImages = 10_649, privateVideos = 1_595, bigFolder = 8_910, albums = 2_130, tracksPerAlbum = 6, commonPhotos = 202, commonVideos = 380),

    /** Per le prestazioni della scansione e della Libreria (P5, P10): ~100 mila file in tutto. */
    CENTOMILA(privateImages = 80_000, privateVideos = 6_000, bigFolder = 8_910, albums = 2_130, tracksPerAlbum = 6, commonPhotos = 8_000, commonVideos = 1_000),

    /** Piccolo, per i test veloci. */
    PICCOLO(privateImages = 280, privateVideos = 20, bigFolder = 120, albums = 10, tracksPerAlbum = 3, commonPhotos = 40, commonVideos = 5),
  }

  private val random = Random(seed)
  private var nextSeed = seed * 1_000_003

  /** I percorsi generati per categoria (per gli scenari e le asserzioni dei test). */
  val generated: MutableMap<String, MutableList<String>> = LinkedHashMap()

  /**
   * Riempie [privateShare] (piatta: 6 cartelle, una molto grande) e [commonShare] (molte cartelle musicali con copertine
   * da ~40 KB, foto e video fino a 9,4 GB virtuali), con date dal 1979, nomi WhatsApp/Screenshot/Pixel, emoji, accenti,
   * percorsi lunghi, nomi uguali a meno delle maiuscole, file a zero byte, corrotti, temporanei in arrivo, cartelle di
   * sistema e nascoste (E-N16).
   */
  fun populate(profile: Profile, privateShare: String = "test", commonShare: String = "test-comune") {
    nas.addShare(privateShare)
    nas.addShare(commonShare)
    val now = nas.nasNow()

    // --- Share privata ------------------------------------------------------------------------------------------------
    // La cartella enorme (E-N5).
    repeat(profile.bigFolder) { i ->
      put(privateShare, "Camera/IMG_${20240101 + i % 365}_${100000 + i}.jpg", jpeg(1_800_000), now - (i + 1) * 600_000L, "foto")
    }
    val others = listOf("WhatsApp Images", "Screenshots", "Download", "Pixel", "Vecchie 1979-2005")
    var images = profile.bigFolder
    var n = 0
    while (images < profile.privateImages) {
      val folder = others[n % others.size]
      val name = when (folder) {
        "WhatsApp Images" -> "IMG-2023${(n % 12 + 1).toString().padStart(2, '0')}15-WA${n.toString().padStart(5, '0')}.jpg"
        "Screenshots" -> "Screenshot_2024-05-${(n % 28 + 1).toString().padStart(2, '0')}-10-00-${n}.png"
        "Pixel" -> "PXL_20240601_$n.jpg"
        "Vecchie 1979-2005" -> "scansione $n.jpg"
        else -> "foto_$n.${if (n % 7 == 0) "heic" else "jpg"}"
      }
      val date = if (folder == "Vecchie 1979-2005") OLD_DATE + n * 86_400_000L else now - (profile.bigFolder + n + 1) * 600_000L
      put(privateShare, "$folder/$name", if (name.endsWith(".png")) png(400_000) else jpeg(1_800_000), date, "foto")
      images++
      n++
    }
    repeat(profile.privateVideos) { i ->
      val size = if (i % 200 == 0) 9_400_000_000L else 120_000_000L
      put(privateShare, "Video/VID_$i.mp4", mp4(size), now - (i + 1) * 86_400_000L, "video")
    }
    // Casi difficili (E-N6, E-N7, E-M12, E-N4, E-N16).
    put(privateShare, "Download/Città 🌅 al tramonto.jpg", jpeg(900_000), now - 10_000_000, "nomi")
    put(privateShare, "Download/" + "nome molto lungo ".repeat(12).trim() + ".jpg", jpeg(500_000), now - 9_000_000, "nomi")
    put(privateShare, "Download/Doppio.JPG", jpeg(700_000), now - 8_000_000, "maiuscole")
    put(privateShare, "Download/vuoto.jpg", NasContent.of(ByteArray(0)), now - 7_000_000, "zero")
    put(privateShare, "Download/finto.jpg", FastContent(50_000, nextSeed++, ByteArray(0)), now - 6_000_000, "corrotti")
    put(privateShare, "Download/in arrivo.mp4.part", mp4(80_000_000), now - 10_000, "arrivo")
    nas.putDir(privateShare, ".recycle", attributes = NasAttributes.HIDDEN)
    put(privateShare, ".recycle/vecchia.jpg", jpeg(800_000), now - 5_000_000, "sistema")
    nas.putDir(privateShare, "@eaDir")
    put(privateShare, "@eaDir/SYNOFILE_THUMB_M.jpg", jpeg(20_000), now - 5_000_000, "sistema")

    // --- Area comune: musica con copertine (E-N14), poche foto, video grandi --------------------------------------------
    repeat(profile.albums) { i ->
      val dir = "Musica/Artista ${i / 10}/Album $i"
      repeat(profile.tracksPerAlbum) { t ->
        val ext = if (t % 5 == 4) "flac" else "mp3"
        put(commonShare, "$dir/${(t + 1).toString().padStart(2, '0')} Traccia.$ext", FastContent(8_000_000, nextSeed++, ByteArray(0)), now - i * 100_000L, "musica")
      }
      val covers = if (i % 5 < 2) 3 else 2
      repeat(covers) { c ->
        put(commonShare, "$dir/${COVER_NAMES[c % COVER_NAMES.size]}", jpeg(40_000), now - i * 100_000L, "copertine")
      }
      put(commonShare, "$dir/album.cue", NasContent.of(ByteArray(900) { 'c'.code.toByte() }), now - i * 100_000L, "altro")
    }
    repeat(profile.commonPhotos) { i ->
      val name = if (i % 4 == 0) "Famiglia/Foto ${i / 20}/immagine_$i.png" else "Famiglia/Foto ${i / 20}/IMG_$i.jpg"
      put(commonShare, name, if (name.endsWith(".png")) png(2_500_000) else jpeg(2_500_000), now - i * 900_000L, "foto")
    }
    repeat(profile.commonVideos) { i ->
      val ext = if (i % 40 == 0) "mkv" else "mp4"
      val size = if (i % 40 == 0) 13_500_000_000L else 129_000_000L
      put(commonShare, "Film/Film $i.$ext", if (ext == "mkv") FastContent(size, nextSeed++, MKV) else mp4(size), now - i * 86_400_000L, "video")
    }
  }

  /**
   * L'albero delle copie dei telefoni (Fase 5): `Backup/<[device]>/AAAA/MM/IMG_AAAAMM_n.jpg`, dal [firstYear] fino a
   * [lastYear] e [lastMonth] (inclusi), [perMonth] foto per mese, una ogni ora dall'inizio del mese. Le cartelle si creano
   * dalla piu' vecchia, cosi' la data di modifica di ogni cartella cresce con l'anno e il mese come su un NAS vero.
   * Restituisce i percorsi in ordine di creazione. Le foto sono virtuali (JPEG).
   */
  fun populateBackup(
    share: String,
    device: String = "Pixel 9",
    firstYear: Int = 2009,
    lastYear: Int,
    lastMonth: Int,
    perMonth: Int = 10,
  ): List<String> {
    nas.addShare(share)
    val paths = ArrayList<String>()
    for (year in firstYear..lastYear) {
      for (month in 1..(if (year == lastYear) lastMonth else 12)) {
        val mm = month.toString().padStart(2, '0')
        val start = java.time.LocalDate.of(year, month, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        repeat(perMonth) { i ->
          val path = "Backup/$device/$year/$mm/IMG_$year${mm}_${i.toString().padStart(4, '0')}.jpg"
          put(share, path, jpeg(1_800_000), start + i * 3_600_000L, "backup")
          paths += path
        }
      }
    }
    return paths
  }

  // --- Scenari del §5.3 --------------------------------------------------------------------------------------------------

  /** Rinomina dal PC di una cartella intera (E-N1, F4): `fileId` conservati, nessun byte cambiato. */
  fun renameFolder(share: String, from: String, to: String) = nas.rename(share, from, to)

  /** Modifica sul posto dal PC (E-N2): stesso `fileId`, byte e data nuovi. */
  fun modifyInPlace(share: String, path: String) = nas.modify(share, path, jpeg(1_900_000))

  /** Cancellazione dal PC (E-N3). */
  fun deleteFromPc(share: String, path: String) = nas.delete(share, path)

  /** Un file nuovo appena arrivato dal PC (E-N4: la data di modifica e' quella di adesso, in ora del NAS). */
  fun arrive(share: String, path: String, size: Long = 1_800_000, modifiedAt: Long = nas.nasNow()): Long =
    nas.putFile(share, path, jpeg(size), modifiedAt).also { generated.getOrPut("arrivi") { mutableListOf() } += path }

  /**
   * "Ransomware" (D10, F9): [count] file della share rinominati con l'estensione `.locked` e byte nuovi. Restituisce i
   * percorsi nuovi.
   */
  fun ransomware(share: String, paths: List<String>, count: Int = 500): List<String> =
    paths.take(count).map { path ->
      val locked = "$path.locked"
      nas.delete(share, path)
      nas.putFile(share, locked, FastContent(1_800_000, nextSeed++, ByteArray(0)))
      locked
    }

  /** Il "ransomware" che cifra sul posto: stesso nome, stesso `fileId`, byte casuali e data nuova. */
  fun encryptInPlace(share: String, paths: List<String>) {
    paths.forEach { nas.modify(share, it, FastContent(1_800_000, nextSeed++, ByteArray(0))) }
  }

  /** La share sparisce (E-R10, E-I8). */
  fun removeShare(share: String) = nas.removeShare(share)

  // --- Contenuti ---------------------------------------------------------------------------------------------------------

  private fun put(share: String, path: String, content: NasContent, modifiedAt: Long, category: String) {
    val real = realContent(path)
    nas.putFile(share, path, real?.let { NasContent.of(it) } ?: content, modifiedAt)
    generated.getOrPut(category) { mutableListOf() } += path
  }

  private fun jpeg(size: Long): NasContent = FastContent(size + random.nextLong(0, 4096), nextSeed++, JPEG)

  private fun png(size: Long): NasContent = FastContent(size, nextSeed++, PNG)

  private fun mp4(size: Long): NasContent = FastContent(size, nextSeed++, MP4)

  /**
   * Come `NasContent.virtual` ma a 8 byte per volta (SplitMix64 su (seme, blocco)): un rilevamento di 12 mila file legge
   * qualche GB virtuale e la generazione byte per byte dominerebbe il tempo dei test. Stessi byte per la stessa posizione.
   */
  private class FastContent(override val size: Long, private val seed: Long, private val head: ByteArray) : NasContent {
    init {
      require(size >= 0 && head.size <= size) { "Contenuto virtuale non valido." }
    }

    override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
      var i = 0
      while (i < len && offset + i < head.size) {
        dst[dstOffset + i] = head[(offset + i).toInt()]
        i++
      }
      while (i < len) {
        val pos = offset + i
        val word = word(pos ushr 3)
        var shift = ((pos and 7L).toInt()) * 8
        while (i < len && shift < 64) {
          dst[dstOffset + i] = (word ushr shift).toByte()
          i++
          shift += 8
        }
      }
    }

    private fun word(block: Long): Long {
      var z = seed + block * -0x61c8864680b583ebL
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      return z xor (z ushr 31)
    }
  }

  companion object {
    /** Un contenuto virtuale veloce (8 byte per volta): [size] byte pseudo-casuali dal [seed], con [head] davanti. */
    fun content(size: Long, seed: Long, head: ByteArray = ByteArray(0)): NasContent = FastContent(size, seed, head)

    /** L'intestazione di un JPEG (`FF D8 FF E1`): [HeadSniffingExtractor] lo riconosce come foto. */
    val JPEG_HEAD: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())

    /** L'intestazione di un MP4 (`ftypisom`). */
    val MP4_HEAD: ByteArray = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)

    /** 1979-06-01: le scansioni di vecchie foto (E-M9). */
    private const val OLD_DATE: Long = 296_870_400_000L

    private val COVER_NAMES = listOf("cover.jpg", "folder.jpg", "AlbumArtSmall.jpg", "front.jpg", "AlbumArt_{ABC}_Large.jpg")

    private val JPEG = JPEG_HEAD
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val MP4 = MP4_HEAD
    private val MKV = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
  }
}
