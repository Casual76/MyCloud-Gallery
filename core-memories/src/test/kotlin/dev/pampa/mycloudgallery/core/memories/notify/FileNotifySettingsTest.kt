package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalTime
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Le preferenze delle notifiche su file (docs/fase9-progetto.md D10, D15, E-NT10; Q4, Q5 (a)): le scelte di serie senza file o con un file che non torna, ogni
 * combinazione che si salva e si rilegge uguale (anche da un oggetto nuovo), scrittura atomica, errore che esce e lascia le scelte com'erano.
 */
class FileNotifySettingsTest {

  private val dirs = ArrayList<File>()

  @After
  fun togliTutto() {
    dirs.forEach { it.deleteRecursively() }
  }

  private fun dir(): File = Files.createTempDirectory("notify-prefs").toFile().also { dirs += it }

  private fun settings(dir: File) = FileNotifySettings(dir, Dispatchers.Unconfined)

  @Test
  fun senzaFileValgonoLeSceltePiuRiservate() {
    val prefs = settings(dir()).prefs.value
    assertThat(prefs).isEqualTo(NotifyPrefs())
    assertThat(prefs.enabled).isTrue()
    assertThat(prefs.hour).isEqualTo(LocalTime.of(19, 0))
    assertThat(prefs.frequency).isEqualTo(NotifyFrequency.FEW_PER_WEEK)
    assertThat(prefs.lockScreen).isEqualTo(LockScreenMode.GENERIC)
  }

  /** Tutte le ore a passi di 15 minuti, le frequenze, le modalita' e acceso/spento: si salvano e si rileggono uguali da un oggetto nuovo. */
  @Test
  fun ogniScelta_siSalvaESiRilegge() = runTest {
    val dir = dir()
    val s = settings(dir)
    for (quarter in 0 until 96) {
      val prefs = NotifyPrefs(
        enabled = quarter % 3 != 0, hour = LocalTime.of(quarter / 4, quarter % 4 * 15), frequency = NotifyFrequency.entries[quarter % NotifyFrequency.entries.size],
        lockScreen = LockScreenMode.entries[quarter % 2],
      )
      s.set(prefs)
      assertThat(s.prefs.value).isEqualTo(prefs)
      assertThat(settings(dir).prefs.value).isEqualTo(prefs)
    }
  }

  /** Un file rovinato (ogni troncamento, ogni bit) vale "non c'e'": scelte di serie, mai un crollo e mai una scelta a caso. */
  @Test
  fun unFileRovinatoValeSceltePiuRiservateESiRiscrive() = runTest {
    val custom = NotifyPrefs(enabled = false, hour = LocalTime.of(8, 45), frequency = NotifyFrequency.ANNIVERSARIES, lockScreen = LockScreenMode.TITLE_AND_PHOTO)
    val good = NotifyPrefsCodec.encode(custom)
    assertThat(NotifyPrefsCodec.decode(good)).isEqualTo(custom)
    for (n in 0 until good.size) assertWithMessage("troncato a $n").that(NotifyPrefsCodec.decode(good.copyOf(n))).isNull()
    for (byte in good.indices) for (bit in 0 until 8) {
      val flipped = good.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
      assertWithMessage("byte $byte, bit $bit").that(NotifyPrefsCodec.decode(flipped)).isNull()
    }
    val dir = dir()
    File(dir, FileNotifySettings.FILE_NAME).writeBytes(good.copyOf(good.size - 2))
    val s = settings(dir)
    assertThat(s.prefs.value).isEqualTo(NotifyPrefs())
    s.set(custom)
    assertThat(settings(dir).prefs.value).isEqualTo(custom)
  }

  /** Valori fuori scelta con il CRC giusto (ora non a passi di 15 minuti, frequenza o modalita' che non esistono) non valgono. */
  @Test
  fun valoriFuoriSceltaNonValgono() {
    fun withCrc(body: String): ByteArray {
      val crc = java.util.zip.CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value
      return (body + "crc " + String.format(java.util.Locale.ROOT, "%08x", crc) + "\n").toByteArray(Charsets.US_ASCII)
    }
    val ok = "MCGS1\nenabled 1\nhour 19:00\nfrequency daily\nlock generic\n"
    assertThat(NotifyPrefsCodec.decode(withCrc(ok))).isNotNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("19:00", "19:10")))).isNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("19:00", "24:00")))).isNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("daily", "off")))).isNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("generic", "all")))).isNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("enabled 1", "enabled 2")))).isNull()
    assertThat(NotifyPrefsCodec.decode(withCrc(ok.replace("MCGS1", "MCGS9")))).isNull()
  }

  /** Il file dice solo le quattro scelte: niente nomi, titoli, luoghi, id. */
  @Test
  fun ilFileDiceSoloLeQuattroScelte() = runTest {
    val dir = dir()
    settings(dir).set(NotifyPrefs(hour = LocalTime.of(7, 30)))
    val lines = File(dir, FileNotifySettings.FILE_NAME).readText(Charsets.US_ASCII).lines().filter { it.isNotEmpty() }
    assertThat(lines.map { it.substringBefore(' ') }).containsExactly("MCGS1", "enabled", "hour", "frequency", "lock", "crc").inOrder()
    assertThat(settings(dir).prefs.value.toString()).doesNotContain("07")
  }

  /** La morte a ogni scrittura: dopo la riapertura le scelte sono quelle di prima o quelle nuove, mai un'altra cosa; un errore del disco esce e il flusso resta com'era. */
  @Test
  fun morteEErroriAOgniScrittura() = runTest {
    val random = Random(11)
    repeat(40) { n ->
      val dir = dir()
      val before = NotifyPrefs(hour = LocalTime.of(random.nextInt(24), random.nextInt(4) * 15), frequency = NotifyFrequency.entries[random.nextInt(3)])
      settings(dir).set(before)
      val after = NotifyPrefs(enabled = random.nextBoolean(), hour = LocalTime.of(random.nextInt(24), random.nextInt(4) * 15), lockScreen = LockScreenMode.entries[random.nextInt(2)])
      val beforeWrite = n % 2 == 0
      val victim = FileNotifySettings(dir, Dying(beforeWrite), Dispatchers.Unconfined)
      assertThrows(IOException::class.java) { runBlocking { victim.set(after) } }
      // Il flusso e' com'era (cambia solo a scrittura riuscita).
      assertThat(victim.prefs.value).isEqualTo(before)
      assertThat(settings(dir).prefs.value).isIn(listOf(before, after))
    }
  }

  @Test
  fun clearTornaAlleSceltePiuRiservate() = runTest {
    val dir = dir()
    val s = settings(dir)
    s.set(NotifyPrefs(enabled = false))
    s.clear()
    assertThat(s.prefs.value).isEqualTo(NotifyPrefs())
    assertThat(File(dir, FileNotifySettings.FILE_NAME).exists()).isFalse()
    s.clear()
  }

  /** Un disco che muore alla prima scrittura: prima di toccare il file o subito dopo averlo scritto intero. */
  private class Dying(private val beforeWrite: Boolean) : LedgerIo {
    override fun read(file: File): ByteArray? = StateFilesPrefsIo.read(file)

    override fun write(file: File, bytes: ByteArray) {
      if (beforeWrite) throw IOException("disco morto")
      StateFilesPrefsIo.write(file, bytes)
      throw IOException("disco morto")
    }

    override fun delete(file: File): Boolean = StateFilesPrefsIo.delete(file)
  }
}
