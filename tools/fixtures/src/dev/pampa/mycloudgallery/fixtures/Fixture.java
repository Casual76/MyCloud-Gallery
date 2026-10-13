package dev.pampa.mycloudgallery.fixtures;

import java.util.function.Supplier;

/**
 * Un file di prova con ciò che ci si aspetta dalla sua lettura. I valori attesi sono scritti **a mano** dalla specifica
 * (docs/fase3-progetto.md §2.2), mai ricavati dall'estrattore: i test li confrontano con l'esito vero e
 * docs/fase3-campo.md li riporta con le stesse parole.
 */
public final class Fixture {

  public final String name;
  /** {@code RELATIVE_PATH} del MediaStore ("DCIM/Camera/"). */
  public final String folder;
  public final String mime;
  /** Come il file sarebbe nel catalogo: la sezione di docs/fase3-campo.md. */
  public final String section;
  public final String description;
  private final Supplier<byte[]> content;
  public final Expect expect;
  /** Il {@code DATE_TAKEN} del MediaStore che il test fornisce (null = nessuno). */
  public Long msDateTakenMs;
  /** Il {@code DATE_MODIFIED} del MediaStore che il test fornisce (null = nessuno). */
  public Long msDateModifiedMs;
  /** Vero se il file va anche sul telefono (il valore atteso non dipende da ciò che scrive il MediaProvider). */
  public boolean onDevice = true;

  public Fixture(String section, String name, String folder, String mime, String description, Supplier<byte[]> content, Expect expect) {
    this.section = section;
    this.name = name;
    this.folder = folder;
    this.mime = mime;
    this.description = description;
    this.content = content;
    this.expect = expect;
  }

  public byte[] bytes() {
    return content.get();
  }

  public Fixture takenAt(long ms) {
    this.msDateTakenMs = ms;
    this.onDevice = false;
    return this;
  }

  public Fixture modifiedAt(long ms) {
    this.msDateModifiedMs = ms;
    this.onDevice = false;
    return this;
  }

  /** Il valore atteso. Un campo nullo non si controlla. */
  public static final class Expect {
    /** "read", "corrupt:TRUNCATED", "corrupt:DECODE_FAILED", "notmedia:ZERO_BYTES", "notmedia:NOT_A_MEDIA_FILE", "unsupported:UNSUPPORTED_FORMAT". */
    public String result = "read";
    public String kind = "PHOTO";
    public Integer width = 64;
    public Integer height = 48;
    /** Il valore EXIF {@code Orientation} equivalente (1..8). */
    public Integer orientation = 1;
    /** L'ora che segnava l'orologio sul posto, ISO senza fuso ("2024-03-12T10:15:30" o con i millisecondi). */
    public String local;
    public Integer offsetMinutes;
    public String confidence;
    public String dateSource;
    /** Il nome del luogo (dataset di prova), "-" = nessun luogo, null = non si controlla. */
    public String place = "-";
    /** I tratti separati da virgola, in ordine alfabetico; "" = nessuno. */
    public String traits = "";
    /** {@code CAMERA}, {@code SCREENSHOT}, {@code WHATSAPP}, {@code QUICK_SHARE}, {@code OTHER}. */
    public String source;
    /** "none" (nessuna parte video), "declared" (dichiarata ma non trovata, E-M16) o il formato: GOOGLE_V1, MICRO_VIDEO, SAMSUNG_SEF. */
    public String motion = "none";
    /** Se c'è una parte video: la sua lunghezza in byte (per Google v1 e MicroVideo è anche la distanza dalla fine del file). */
    public Long motionLength;
    /** La proporzione mostrata attesa, in millesimi (larghezza / altezza x 1000), dopo l'orientamento. */
    public Integer aspectMilli;

    public static Expect read() {
      return new Expect();
    }

    public static Expect result(String result) {
      Expect e = new Expect();
      e.result = result;
      e.kind = null;
      e.width = null;
      e.height = null;
      e.orientation = null;
      e.place = null;
      e.traits = null;
      e.motion = null;
      return e;
    }

    public Expect kind(String v) { kind = v; return this; }
    public Expect size(Integer w, Integer h) { width = w; height = h; return this; }
    public Expect orientation(int v) { orientation = v; return this; }
    public Expect date(String localIso, int offset, String conf, String src) {
      local = localIso;
      offsetMinutes = offset;
      confidence = conf;
      dateSource = src;
      return this;
    }
    public Expect place(String v) { place = v; return this; }
    public Expect traits(String v) { traits = v; return this; }
    public Expect source(String v) { source = v; return this; }
    public Expect motion(String format, Long length) { motion = format; motionLength = length; return this; }
    public Expect aspect(int milli) { aspectMilli = milli; return this; }

    /** La riga "Atteso" di docs/fase3-campo.md, nelle parole dell'app. */
    public String human() {
      switch (result) {
        case "read":
          break;
        case "corrupt:TRUNCATED":
          return "Non leggibile (file troncato); nessuna eccezione, la cella è \"non leggibile\"";
        case "corrupt:DECODE_FAILED":
          return "Non leggibile (file rovinato); la cella è \"non leggibile\"";
        case "notmedia:ZERO_BYTES":
          return "Ignorato e contato (zero byte); mai nella Libreria";
        case "notmedia:NOT_A_MEDIA_FILE":
          return "Ignorato e contato (non è un media); mai nella Libreria";
        case "unsupported:UNSUPPORTED_FORMAT":
          return "Formato non supportato: resta in Libreria senza metadati";
        default:
          return result;
      }
      StringBuilder sb = new StringBuilder();
      sb.append(kindWord());
      if (width != null && height != null) sb.append(", ").append(width).append("×").append(height);
      if (orientation != null && orientation != 1) sb.append(", orientamento EXIF ").append(orientation);
      if (local != null) {
        sb.append("; ").append(humanDate());
        sb.append(" (").append(offsetText(offsetMinutes)).append(")");
        sb.append(", ").append(confidenceWord());
      }
      if (source != null) sb.append("; sorgente ").append(sourceWord());
      if (place != null && !place.equals("-")) sb.append("; luogo ").append(place);
      if (traits != null && !traits.isEmpty()) sb.append("; tratti ").append(traits);
      if (motion != null && !motion.equals("none")) {
        sb.append("; ").append(motion.equals("declared") ? "motion photo dichiarata, parte video non riproducibile" : "parte video " + motion);
      }
      return sb.toString();
    }

    private String kindWord() {
      switch (kind == null ? "" : kind) {
        case "VIDEO": return "Video";
        case "MOTION_PHOTO": return "Motion photo";
        case "RAW_PHOTO": return "RAW";
        case "ANIMATED_IMAGE": return "Animata";
        default: return "Foto";
      }
    }

    private String humanDate() {
      // 2024-03-12T10:15:30[.123] -> 12/03/2024 10:15:30[.123]
      String date = local.substring(0, 10);
      String time = local.substring(11);
      return date.substring(8, 10) + "/" + date.substring(5, 7) + "/" + date.substring(0, 4) + " " + time;
    }

    private static String offsetText(Integer minutes) {
      int m = minutes == null ? 0 : minutes;
      String sign = m < 0 ? "-" : "+";
      int abs = Math.abs(m);
      return "UTC" + sign + (abs / 60) + (abs % 60 == 0 ? "" : ":" + String.format("%02d", abs % 60));
    }

    private String confidenceWord() {
      switch (confidence == null ? "" : confidence) {
        case "EXACT": return "esatta";
        case "OFFSET_ASSUMED": return "ora certa, fuso supposto";
        case "ESTIMATED": return "stimata";
        default: return "";
      }
    }

    private String sourceWord() {
      switch (source) {
        case "CAMERA": return "Fotocamera";
        case "SCREENSHOT": return "Screenshot";
        case "WHATSAPP": return "WhatsApp";
        case "QUICK_SHARE": return "Quick Share";
        default: return "Altro";
      }
    }
  }
}
