package dev.pampa.mycloudgallery.fixtures.ocr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Il generatore del SET OCR SINTETICO (docs/fase6-progetto.md §5.3, P9): scontrini di negozi inventati, chat e schermate finte, cartelli
 * con accenti, apostrofi ed euro, testo da 10 a 40 px, dritto, ruotato, sfocato, con rumore, con artefatti JPEG e in prospettiva, a 1024 e
 * a 2048 px. Nessun dato personale: tutto il testo e' inventato.
 *
 * <pre>
 *   powershell -File tools\fixtures\generate-ocr.ps1                       genera il set in tools\fixtures\out\MCG-ocr\
 *   powershell -File tools\fixtures\generate-ocr.ps1 -Resoconto letto.tsv  l'errore per carattere dalla lettura di un lettore
 *   powershell -File tools\fixtures\generate-ocr.ps1 -LettoreFinto         prova del resoconto con un lettore finto (NON e' una misura)
 * </pre>
 *
 * Scrive {@code immagini/*.jpg} e {@code manifest.tsv} (id, file, genere, larghezza, px, degradazione, testo atteso). Il testo letto da un lettore
 * (ML Kit sul dispositivo: la corsa di debug "Lettura di prova" scrive {@code letto.tsv}) ha le stesse chiavi: {@code id<TAB>testo}.
 * Non si committa l'uscita: {@code out/} e' nel {@code .gitignore}.
 */
public final class OcrSetGenerator {

  private OcrSetGenerator() {}

  public static void main(String[] args) throws IOException {
    File out = new File("tools/fixtures/out");
    boolean quick = false;
    boolean fake = false;
    String readFile = null;
    String label = null;
    long seed = 1;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--rapido":
          quick = true;
          break;
        case "--lettore-finto":
          fake = true;
          break;
        case "--resoconto":
          readFile = args[++i];
          break;
        case "--etichetta":
          label = args[++i];
          break;
        case "--seme":
          seed = Long.parseLong(args[++i]);
          break;
        default:
          out = new File(args[i]);
      }
    }
    File dir = new File(out, "MCG-ocr");
    if (fake) {
      File fakeRead = new File(dir, "letto-finto.tsv");
      OcrFakeReader.write(new File(dir, "manifest.tsv"), fakeRead);
      System.out.println(OcrReport.write(dir, fakeRead, "FINTO: simulazione del resoconto, non e' una misura di ML Kit"));
      return;
    }
    if (readFile != null) {
      System.out.println(OcrReport.write(dir, new File(readFile), label == null ? "lettore sconosciuto" : label));
      return;
    }
    generate(dir, quick, seed);
  }

  static void generate(File dir, boolean quick, long seed) throws IOException {
    File images = new File(dir, "immagini");
    if (!images.isDirectory() && !images.mkdirs()) throw new IOException("Non riesco a creare " + images);
    List<String> manifest = new ArrayList<>();
    manifest.add("id\tfile\tgenere\tlarghezza\tpx\tdegradazione\ttesto");
    long bytes = 0;
    List<OcrCases.Case> cases = OcrCases.plan(quick, seed);
    for (OcrCases.Case c : cases) {
      OcrRender.Scene scene = OcrRender.render(c.genre, c.width, c.px, c.seed + c.px);
      byte[] jpeg = OcrDegrade.apply(scene.image, c.kind, scene.background, c.seed + c.px * 31L + c.width, 0.9f);
      String file = "immagini/" + c.id() + ".jpg";
      Files.write(new File(dir, file).toPath(), jpeg);
      bytes += jpeg.length;
      manifest.add(c.id() + "\t" + file + "\t" + c.genre.code + "\t" + c.width + "\t" + c.px + "\t" + c.kind.code + "\t"
          + Tsv.escape(String.join("\n", scene.lines)));
    }
    Files.write(new File(dir, "manifest.tsv").toPath(), manifest, StandardCharsets.UTF_8);
    Files.write(new File(dir, "LEGGIMI.txt").toPath(), readme(cases.size()).getBytes(StandardCharsets.UTF_8));
    System.out.println(cases.size() + " immagini, " + (bytes / 1024 / 1024) + " MB in " + dir.getPath());
  }

  private static String readme(int count) {
    return "Set OCR sintetico di MyCloud Gallery (" + count + " immagini): tutto il testo e' inventato, nessun dato personale.\n"
        + "manifest.tsv: id, file, genere, larghezza, px (altezza del carattere nell'immagine), degradazione, testo atteso (a capo = \\n).\n"
        + "Sul telefono (build debug): adb push MCG-ocr /sdcard/Android/data/<id>.debug/files/MCG-ocr, poi Diagnostica > Sviluppo >\n"
        + "Valutazione della ricerca > Lettura di prova. Il risultato (letto.tsv, id<TAB>testo letto) si copia sul PC con adb pull e si\n"
        + "passa a generate-ocr.ps1 -Resoconto. Il set si cancella dal telefono alla fine.\n";
  }

  /** Il formato delle colonne del manifesto e del testo letto: tab e a capo come {@code \t} e {@code \n}, il backslash raddoppiato. */
  static final class Tsv {
    private Tsv() {}

    static String escape(String s) {
      return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    static String unescape(String s) {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\\' && i + 1 < s.length()) {
          char next = s.charAt(++i);
          out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
        } else {
          out.append(c);
        }
      }
      return out.toString();
    }
  }

  /** Per i test e per chi vuole una sola scena: l'immagine pulita di un caso. */
  static BufferedImage clean(OcrTexts.Genre genre, int width, int px, long seed) {
    return OcrRender.render(genre, width, px, seed).image;
  }
}
