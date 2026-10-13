package dev.pampa.mycloudgallery.fixtures;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Vector;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** JPEG, PNG, GIF, BMP e WebP di prova, anche con EXIF e XMP. Solo JDK: nessuna libreria. */
public final class ImageFixtures {

  private ImageFixtures() {}

  // ---- immagini di base ----------------------------------------------------------------------------------------

  /** Un'immagine con una "F" asimmetrica: se la si vede dritta, lo è davvero (serve agli otto orientamenti). */
  public static Img uprightF(int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(0x2A6FB0));
    g.fillRect(0, 0, w, h);
    g.setColor(Color.WHITE);
    int u = Math.max(1, Math.min(w, h) / 8);
    g.fillRect(u, u, u, h - 2 * u); // asta
    g.fillRect(u, u, w / 2, u); // barra alta
    g.fillRect(u, h / 2 - u / 2, w / 3, u); // barra media
    g.setColor(new Color(0xE0603A));
    g.fillRect(w - 2 * u, h - 2 * u, u, u); // un marcatore in basso a destra
    g.dispose();
    return new Img(img);
  }

  /** Cio' che si vede (pixel dopo l'orientamento) per un valore EXIF {@code Orientation} 1..8. */
  public static Img displayed(Img storedImage, int orientation) {
    BufferedImage stored = storedImage.image;
    int w = stored.getWidth();
    int h = stored.getHeight();
    boolean swap = orientation >= 5;
    BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int dx;
        int dy;
        switch (orientation) {
          case 2: dx = w - 1 - x; dy = y; break;
          case 3: dx = w - 1 - x; dy = h - 1 - y; break;
          case 4: dx = x; dy = h - 1 - y; break;
          case 5: dx = y; dy = x; break;
          case 6: dx = h - 1 - y; dy = x; break;
          case 7: dx = h - 1 - y; dy = w - 1 - x; break;
          case 8: dx = y; dy = w - 1 - x; break;
          default: dx = x; dy = y; break;
        }
        out.setRGB(dx, dy, stored.getRGB(x, y));
      }
    }
    return new Img(out);
  }

  /** I pixel da memorizzare perché, con {@code Orientation = o}, si veda {@code upright}. */
  public static Img storedFor(Img upright, int orientation) {
    int inverse = orientation == 6 ? 8 : orientation == 8 ? 6 : orientation;
    return displayed(upright, inverse);
  }

  public static Img solid(int w, int h, int rgb) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(rgb));
    g.fillRect(0, 0, w, h);
    g.dispose();
    return new Img(img);
  }

  /** Rumore deterministico: un file che non si comprime a niente (per i file da tagliare a metà). */
  public static Img noise(int w, int h, long seed) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    java.util.Random random = new java.util.Random(seed);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, random.nextInt(0x1000000));
    return new Img(img);
  }

  // ---- JPEG ----------------------------------------------------------------------------------------------------

  public static byte[] jpegBytes(Img img) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(img.image, "jpg", out)) throw new IllegalStateException("nessun codificatore JPEG");
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Un JPEG {@code w x h} a tinta unita. */
  public static byte[] jpeg(int w, int h, int rgb) {
    return jpegBytes(solid(w, h, rgb));
  }

  /** Un JPEG con EXIF e XMP (entrambi opzionali) inseriti subito dopo l'SOI, come fanno le fotocamere. */
  public static byte[] jpeg(Img img, ExifWriter.Spec exif, String xmp) {
    byte[] base = jpegBytes(img);
    Bytes out = new Bytes().raw(Bytes.prefix(base, 2));
    if (exif != null) out.raw(app1(ExifWriter.jpegApp1Payload(exif)));
    if (xmp != null) out.raw(app1(new Bytes().ascii("http://ns.adobe.com/xap/1.0/").u8(0).utf8(xmp).toBytes()));
    out.raw(java.util.Arrays.copyOfRange(base, 2, base.length));
    return out.toBytes();
  }

  public static byte[] jpeg(int w, int h, int rgb, ExifWriter.Spec exif, String xmp) {
    return jpeg(solid(w, h, rgb), exif, xmp);
  }

  private static byte[] app1(byte[] payload) {
    if (payload.length > 65533) throw new IllegalArgumentException("segmento APP1 troppo grande");
    return new Bytes().u8(0xFF).u8(0xE1).u16be(payload.length + 2).raw(payload).toBytes();
  }

  /** Il JPEG di una miniatura Exif (160 x 120 al massimo). */
  public static byte[] exifThumbnail(int rgb) {
    return jpeg(40, 30, rgb);
  }

  // ---- PNG -----------------------------------------------------------------------------------------------------

  public static byte[] pngBytes(Img img) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(img.image, "png", out)) throw new IllegalStateException("nessun codificatore PNG");
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Un PNG con i chunk {@code eXIf} e iTXt XMP (opzionali) subito dopo IHDR. */
  public static byte[] png(Img img, ExifWriter.Spec exif, String xmp) {
    byte[] base = pngBytes(img);
    int afterIhdr = 8 + 12 + 13; // firma + IHDR (lunghezza, tipo, 13 byte, CRC)
    Bytes out = new Bytes().raw(Bytes.prefix(base, afterIhdr));
    if (exif != null) out.raw(pngChunk("eXIf", ExifWriter.tiff(exif)));
    if (xmp != null) {
      byte[] body = new Bytes().ascii("XML:com.adobe.xmp").u8(0).u8(0).u8(0).u8(0).u8(0).utf8(xmp).toBytes();
      out.raw(pngChunk("iTXt", body));
    }
    out.raw(java.util.Arrays.copyOfRange(base, afterIhdr, base.length));
    return out.toBytes();
  }

  private static byte[] pngChunk(String type, byte[] data) {
    CRC32 crc = new CRC32();
    byte[] t = Bytes.ascii4(type);
    crc.update(t);
    crc.update(data);
    return new Bytes().u32be(data.length).raw(t).raw(data).u32be(crc.getValue()).toBytes();
  }

  // ---- GIF e BMP -----------------------------------------------------------------------------------------------

  public static byte[] gif(int w, int h, int rgb) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(solid(w, h, rgb).image, "gif", out)) throw new IllegalStateException("nessun codificatore GIF");
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] bmp(int w, int h, int rgb) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(solid(w, h, rgb).image, "bmp", out)) throw new IllegalStateException("nessun codificatore BMP");
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- WebP ----------------------------------------------------------------------------------------------------

  /** Scrive bit nell'ordine del WebP lossless (dal meno significativo). */
  private static final class BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current;
    private int used;

    void bits(long value, int count) {
      for (int i = 0; i < count; i++) {
        current |= (int) ((value >> i) & 1) << used;
        if (++used == 8) {
          out.write(current);
          current = 0;
          used = 0;
        }
      }
    }

    byte[] finish() {
      if (used > 0) out.write(current);
      return out.toByteArray();
    }
  }

  /**
   * Il flusso di un WebP lossless {@code w x h} a tinta unita ({@code argb}): nessuna trasformazione, nessuna cache, cinque
   * codici di prefisso "semplici" a un solo simbolo (zero bit per pixel). È un WebP vero: lo apre qualunque decodificatore.
   */
  public static byte[] vp8lSolid(int w, int h, int argb) {
    BitWriter b = new BitWriter();
    b.bits(0x2F, 8);
    b.bits(w - 1, 14);
    b.bits(h - 1, 14);
    b.bits(((argb >>> 24) != 0xFF) ? 1 : 0, 1);
    b.bits(0, 3); // versione
    b.bits(0, 1); // nessuna trasformazione
    b.bits(0, 1); // nessuna cache dei colori
    b.bits(0, 1); // nessun meta prefisso
    int[] symbols = {(argb >> 8) & 0xFF, (argb >> 16) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF}; // verde, rosso, blu, alfa
    for (int s : symbols) {
      b.bits(1, 1); // codice semplice
      b.bits(0, 1); // un solo simbolo
      b.bits(1, 1); // simbolo a 8 bit
      b.bits(s, 8);
    }
    b.bits(1, 1); // distanza: codice semplice
    b.bits(0, 1);
    b.bits(0, 1); // simbolo a 1 bit
    b.bits(0, 1); // il simbolo 0
    return b.finish();
  }

  private static byte[] riffChunk(String type, byte[] data) {
    Bytes b = new Bytes().ascii(type).u32le(data.length).raw(data);
    if ((data.length & 1) == 1) b.u8(0);
    return b.toBytes();
  }

  private static byte[] vp8x(int flags, int w, int h) {
    return riffChunk("VP8X", new Bytes().u8(flags).zeros(3).u24le(w - 1).u24le(h - 1).toBytes());
  }

  private static byte[] riff(byte[]... chunks) {
    int total = 4;
    for (byte[] c : chunks) total += c.length;
    Bytes b = new Bytes().ascii("RIFF").u32le(total).ascii("WEBP");
    for (byte[] c : chunks) b.raw(c);
    return b.toBytes();
  }

  /** Un WebP statico a tinta unita, con EXIF e XMP opzionali (allora il contenitore è esteso, VP8X). */
  public static byte[] webp(int w, int h, int argb, ExifWriter.Spec exif, String xmp) {
    byte[] image = riffChunk("VP8L", vp8lSolid(w, h, argb));
    if (exif == null && xmp == null) return riff(image);
    int flags = (exif != null ? 0x08 : 0) | (xmp != null ? 0x04 : 0) | (((argb >>> 24) != 0xFF) ? 0x10 : 0);
    java.util.List<byte[]> chunks = new java.util.ArrayList<>();
    chunks.add(vp8x(flags, w, h));
    chunks.add(image);
    if (exif != null) chunks.add(riffChunk("EXIF", ExifWriter.tiff(exif)));
    if (xmp != null) chunks.add(riffChunk("XMP ", Bytes.concat(xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    return riff(chunks.toArray(new byte[0][]));
  }

  /** Un WebP animato: [frames] fotogrammi a tinta unita (blocco ANIM e un ANMF per fotogramma). */
  public static byte[] webpAnimated(int w, int h, int[] frameColors, int frameMs) {
    java.util.List<byte[]> chunks = new java.util.ArrayList<>();
    chunks.add(vp8x(0x02, w, h));
    chunks.add(riffChunk("ANIM", new Bytes().u32le(0xFFFFFFFFL).u16le(0).toBytes()));
    for (int color : frameColors) {
      byte[] frame = riffChunk("VP8L", vp8lSolid(w, h, color));
      byte[] anmf = new Bytes().u24le(0).u24le(0).u24le(w - 1).u24le(h - 1).u24le(frameMs).u8(0).raw(frame).toBytes();
      chunks.add(riffChunk("ANMF", anmf));
    }
    return riff(chunks.toArray(new byte[0][]));
  }

  // ---- JPEG enorme (200 MP) ------------------------------------------------------------------------------------

  /**
   * Il contenuto di un'immagine "virtuale": ogni byte si calcola quando il codificatore lo legge, quindi un JPEG da 200 MP non
   * occupa mai 600 MB di memoria. Il codificatore del JDK vuole un unico {@code Raster} dell'immagine intera e ne legge una riga per
   * volta ({@code createChild} + {@code setRect}): un {@code DataBuffer} che genera i byte al volo gli basta.
   */
  private static final class ProceduralBuffer extends DataBuffer {
    private final int width;
    private final int height;

    ProceduralBuffer(int width, int height) {
      super(DataBuffer.TYPE_BYTE, width * height * 3);
      this.width = width;
      this.height = height;
    }

    @Override public int getElem(int bank, int i) {
      int pixel = i / 3;
      int channel = i - pixel * 3; // 0 = B, 1 = G, 2 = R (l'ordine del sample model: 2, 1, 0)
      int x = pixel % width;
      int y = pixel / width;
      // Un gradiente con una grana pseudo casuale: pesa come una foto vera (circa 70 MB a 200 MP).
      int noise = (int) ((((long) x * 73856093L) ^ ((long) y * 19349663L)) * 0x9E3779B1L >>> 28) & 0xF;
      long value;
      switch (channel) {
        case 0: value = 40 + (x * 150L / width) + noise * 3L; break;
        case 1: value = 60 + (y * 120L / height) + noise * 3L; break;
        default: value = 90 + ((x + (long) y) * 80L / (width + (long) height)) + noise * 3L; break;
      }
      return (int) (value & 0xFF);
    }

    @Override public void setElem(int bank, int i, int val) {
      throw new UnsupportedOperationException("immagine generata: non si scrive");
    }
  }

  /** Un'immagine "virtuale" (vedi {@link ProceduralBuffer}): una sola tessera, grande come l'immagine. */
  private static final class ProceduralImage implements RenderedImage {
    private final int width;
    private final int height;
    private final ColorModel colorModel = new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR).getColorModel();
    private final SampleModel sampleModel;
    private final Raster raster;

    ProceduralImage(int width, int height) {
      this.width = width;
      this.height = height;
      this.sampleModel = new PixelInterleavedSampleModel(DataBuffer.TYPE_BYTE, width, height, 3, width * 3, new int[] {2, 1, 0});
      this.raster = Raster.createRaster(sampleModel, new ProceduralBuffer(width, height), new Point(0, 0));
    }

    @Override public Vector<RenderedImage> getSources() { return null; }
    @Override public Object getProperty(String name) { return java.awt.Image.UndefinedProperty; }
    @Override public String[] getPropertyNames() { return null; }
    @Override public ColorModel getColorModel() { return colorModel; }
    @Override public SampleModel getSampleModel() { return sampleModel; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int getMinX() { return 0; }
    @Override public int getMinY() { return 0; }
    @Override public int getNumXTiles() { return 1; }
    @Override public int getNumYTiles() { return 1; }
    @Override public int getMinTileX() { return 0; }
    @Override public int getMinTileY() { return 0; }
    @Override public int getTileWidth() { return width; }
    @Override public int getTileHeight() { return height; }
    @Override public int getTileGridXOffset() { return 0; }
    @Override public int getTileGridYOffset() { return 0; }
    @Override public Raster getTile(int tileX, int tileY) { return raster; }
    @Override public Raster getData() { return raster; }
    @Override public Raster getData(Rectangle r) { return raster.createChild(r.x, r.y, r.width, r.height, r.x, r.y, null); }

    @Override public WritableRaster copyData(WritableRaster target) {
      target.setRect(getData(target.getBounds()));
      return target;
    }
  }

  /**
   * Scrive un JPEG {@code w x h} su disco senza tenerlo in memoria. Con EXIF opzionale (inserito dopo, riscrivendo l'inizio
   * del file in un secondo file temporaneo).
   */
  public static void writeLargeJpeg(File target, int w, int h, ExifWriter.Spec exif) throws IOException {
    File plain = new File(target.getPath() + ".tmp");
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(0.85f);
    try (ImageOutputStream out = ImageIO.createImageOutputStream(plain)) {
      writer.setOutput(out);
      writer.write(null, new IIOImage(new ProceduralImage(w, h), null, null), param);
    } finally {
      writer.dispose();
    }
    try (OutputStream out = Files.newOutputStream(target.toPath()); java.io.InputStream in = Files.newInputStream(plain.toPath())) {
      byte[] soi = in.readNBytes(2);
      out.write(soi);
      if (exif != null) out.write(app1(ExifWriter.jpegApp1Payload(exif)));
      in.transferTo(out);
    }
    Files.delete(plain.toPath());
  }
}
