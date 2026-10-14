package dev.pampa.mycloudgallery.fixtures;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Il generatore dei campioni della PREPARAZIONE per il telefono (docs/fase4-progetto.md §2.4, P6; prova sul campo F6).
 *
 * <pre>
 *   powershell -File tools\fixtures\generate-prepare.ps1 [cartella-di-uscita] [-SkipBig] [-NoFfmpeg]
 * </pre>
 *
 * Scrive in {@code <uscita>/MCG-prepara/}:
 * <ul>
 *   <li>sempre (solo JDK): video con indice in coda e in testa, video "verticale" (rotazione 90), APV finto, DNG piccolo
 *       con orientamento 6 e 1: strutture vere con fotogrammi finti, che servono a provare i percorsi di lettura e di
 *       errore, non la decodifica;</li>
 *   <li>se {@code ffmpeg} e' nel PATH: gli stessi in versione <b>decodificabile</b> (H.264 con l'indice in coda, uno
 *       verticale, tre HEIC veri, un APV vero se l'encoder c'e'), per misurare sul telefono tempi e byte letti;</li>
 *   <li>il JPEG da 200 MP (circa 70 MB), a meno di {@code --skip-big};</li>
 *   <li>{@code MANIFEST.tsv} con cosa ci si aspetta da ogni file.</li>
 * </ul>
 * Non committare l'uscita: {@code tools/fixtures/out/} e' gia' nel {@code .gitignore} (regola {@code out/}).
 */
public final class PrepareFixtureGenerator {

  private PrepareFixtureGenerator() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    File out = new File("tools/fixtures/out");
    boolean skipBig = false;
    boolean noFfmpeg = false;
    for (String a : args) {
      if (a.equals("--skip-big")) skipBig = true;
      else if (a.equals("--no-ffmpeg")) noFfmpeg = true;
      else out = new File(a);
    }
    File dir = new File(out, "MCG-prepara");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Non riesco a creare " + dir);
    List<String> manifest = new ArrayList<>();
    manifest.add("file\tatteso");

    PrepareFixtures.VideoSpec tail = new PrepareFixtures.VideoSpec();
    write(dir, "video-indice-in-coda-finto.mp4", PrepareFixtures.video(tail).toBytes(), manifest,
        "Video 1920x1080, 90 s, avc1, indice (moov) in coda, fotogrammi finti: byte letti = indice + fotogrammi, mai il file; non decodificabile");

    PrepareFixtures.VideoSpec head = new PrepareFixtures.VideoSpec();
    head.moovLast = false;
    write(dir, "video-indice-in-testa-finto.mp4", PrepareFixtures.video(head).toBytes(), manifest,
        "Come sopra con l'indice in testa");

    PrepareFixtures.VideoSpec portrait = new PrepareFixtures.VideoSpec();
    portrait.rotation = 90;
    write(dir, "video-verticale-finto.mp4", PrepareFixtures.video(portrait).toBytes(), manifest,
        "Memorizzato 1920x1080 con rotazione 90: si mostra 1080x1920");

    PrepareFixtures.VideoSpec apv = new PrepareFixtures.VideoSpec();
    apv.sampleEntry = "apv1";
    apv.durationMs = 6_000;
    apv.width = 1280;
    apv.height = 720;
    write(dir, "apv-finto.mp4", PrepareFixtures.video(apv).toBytes(), manifest,
        "Sample entry apv1: senza decoder APV il risultato e' DECODER_MISSING (D14) senza aver letto altro che l'indice");

    write(dir, "dng-piccolo-orientamento6.dng", PrepareFixtures.smallDng(128, 96, 6), manifest,
        "DNG 128x96, mosaico RGGB 16 bit, Orientation 6: l'anteprima si vede 96x128 con rosso in alto a destra (ruotato di 90 gradi orari)");
    write(dir, "dng-piccolo.dng", PrepareFixtures.smallDng(128, 96, 1), manifest,
        "DNG 128x96, Orientation 1: rosso in alto a sinistra, verde in alto a destra, blu in basso a sinistra, giallo in basso a destra");

    if (!noFfmpeg && ffmpegAvailable()) {
      realFiles(dir, manifest);
    } else {
      System.out.println("ffmpeg non trovato (o --no-ffmpeg): salto i campioni decodificabili (HEIC, video e APV veri).");
    }

    if (!skipBig) {
      ExifWriter.Spec exif = new ExifWriter.Spec();
      exif.make = "samsung";
      exif.model = "SM-S931B";
      exif.dateOriginal = "2024:03:12 10:15:30";
      exif.offsetOriginal = "+01:00";
      File big = new File(dir, "grande-200mp.jpg");
      long start = System.currentTimeMillis();
      ImageFixtures.writeLargeJpeg(big, 16320, 12240, exif);
      manifest.add("grande-200mp.jpg\tFoto 16320x12240 (200 MP): le uscite escono a 2048 px di lato lungo, la memoria resta entro PrepBudget");
      System.out.println("grande-200mp.jpg: " + (big.length() / 1_000_000) + " MB in " + (System.currentTimeMillis() - start) / 1000 + " s");
    }

    Files.write(new File(dir, "MANIFEST.tsv").toPath(), String.join("\n", manifest).concat("\n").getBytes(StandardCharsets.UTF_8));
    System.out.println((manifest.size() - 1) + " file in " + dir.getAbsolutePath());
  }

  private static void write(File dir, String name, byte[] bytes, List<String> manifest, String expected) throws IOException {
    Files.write(new File(dir, name).toPath(), bytes);
    manifest.add(name + "\t" + expected);
    System.out.println(name + ": " + bytes.length / 1000 + " KB");
  }

  // ---- ffmpeg --------------------------------------------------------------------------------------------------

  private static boolean ffmpegAvailable() {
    try {
      Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /** Esegue ffmpeg; false (con l'errore stampato) se non riesce: un campione in meno, non un generatore fermo. */
  private static boolean ffmpeg(String... args) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>();
    cmd.add("ffmpeg");
    cmd.addAll(Arrays.asList("-hide_banner", "-loglevel", "error", "-y"));
    cmd.addAll(Arrays.asList(args));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    boolean ok = p.waitFor(300, TimeUnit.SECONDS) && p.exitValue() == 0;
    if (!ok) System.out.println("ffmpeg " + String.join(" ", args) + "\n  non riuscito: " + output.strip());
    return ok;
  }

  private static void realFiles(File dir, List<String> manifest) throws IOException, InterruptedException {
    File tmp = Files.createTempDirectory("mcg-prepara").toFile();
    try {
      // Video H.264 vero con l'indice in coda (ffmpeg scrive il moov in fondo se non gli si chiede +faststart).
      File video = new File(dir, "reale-video-indice-in-coda.mp4");
      if (ffmpeg("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
          "-t", "75", "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-g", "60", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
          video.getPath())) {
        manifest.add(video.getName() + "\tVideo H.264 1280x720, 75 s, indice in coda: 3 fotogrammi chiave circa (uno ogni 30 s) piu' la copertina");
        // Lo stesso con la rotazione 90 nella matrice: un video girato in verticale.
        byte[] bytes = Files.readAllBytes(video.toPath());
        File vertical = new File(dir, "reale-video-verticale.mp4");
        Files.write(vertical.toPath(), patchRotation(bytes, 90));
        manifest.add(vertical.getName() + "\tCome sopra con rotazione 90: i fotogrammi devono uscire verticali (720x1280), mai ruotati due volte");
        Files.write(new File(dir, "reale-video-a-testa-in-giu.mp4").toPath(), patchRotation(bytes, 180));
        manifest.add("reale-video-a-testa-in-giu.mp4\tCome sopra con rotazione 180: i fotogrammi escono dritti");
      }

      // HEIC veri: un solo fotogramma HEVC ricomposto in un contenitore HEIF.
      File hevc = new File(tmp, "foto.mp4");
      if (ffmpeg("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=1", "-frames:v", "1", "-c:v", "libx265", "-pix_fmt", "yuv420p", "-tag:v", "hvc1",
          "-x265-params", "log-level=error", hevc.getPath())) {
        byte[] mp4 = Files.readAllBytes(hevc.toPath());
        write(dir, "reale-heic.heic", PrepareFixtures.heicFromHevcMp4(mp4, -1, 0), manifest, "HEIC 640x480 senza rotazione");
        write(dir, "reale-heic-irot.heic", PrepareFixtures.heicFromHevcMp4(mp4, 1, 0), manifest,
            "HEIC con irot=1 (90 gradi antiorari): il sistema lo ruota, si vede 480x640 e non si ruota due volte");
        write(dir, "reale-heic-exif6.heic", PrepareFixtures.heicFromHevcMp4(mp4, -1, 6), manifest,
            "HEIC senza irot e con Exif Orientation 6: lo ruota la preparazione (480x640)");
      }

      // APV vero, se l'encoder di questo ffmpeg c'e' (Android 16 ha il decoder software: dipende dal telefono).
      File apv = new File(dir, "reale-apv.mp4");
      if (ffmpeg("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "3", "-c:v", "liboapv", "-pix_fmt", "yuv422p10le", "-b:v", "20M",
          apv.getPath())) {
        manifest.add(apv.getName() + "\tAPV vero 640x360: dove manca il decoder, DECODER_MISSING; dove c'e', 3 fotogrammi");
      }
    } finally {
      File[] left = tmp.listFiles();
      if (left != null) for (File f : left) f.delete();
      tmp.delete();
    }
  }

  /** Riscrive la matrice di rotazione dei {@code tkhd} delle tracce con un'immagine (larghezza diversa da zero). */
  static byte[] patchRotation(byte[] mp4, int rotation) {
    byte[] copy = mp4.clone();
    long one = 0x00010000L;
    long[] m;
    switch (rotation) {
      case 90: m = new long[] {0, one, 0, -one, 0, 0, 0, 0, 0x40000000L}; break;
      case 180: m = new long[] {-one, 0, 0, 0, -one, 0, 0, 0, 0x40000000L}; break;
      case 270: m = new long[] {0, -one, 0, one, 0, 0, 0, 0, 0x40000000L}; break;
      default: m = new long[] {one, 0, 0, 0, one, 0, 0, 0, 0x40000000L}; break;
    }
    int at = 0;
    while ((at = indexOf(copy, new byte[] {'t', 'k', 'h', 'd'}, at)) >= 0) {
      int payload = at + 4;
      int version = copy[payload] & 0xFF;
      int matrix = payload + (version == 1 ? 52 : 40);
      int widthAt = matrix + 36;
      if (widthAt + 8 <= copy.length && (((copy[widthAt] & 0xFF) << 8) | (copy[widthAt + 1] & 0xFF)) != 0) {
        for (int i = 0; i < 9; i++) {
          long v = m[i];
          copy[matrix + i * 4] = (byte) (v >> 24);
          copy[matrix + i * 4 + 1] = (byte) (v >> 16);
          copy[matrix + i * 4 + 2] = (byte) (v >> 8);
          copy[matrix + i * 4 + 3] = (byte) v;
        }
      }
      at += 4;
    }
    return copy;
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = from; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
