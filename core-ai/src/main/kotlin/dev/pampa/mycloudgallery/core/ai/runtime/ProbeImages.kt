package dev.pampa.mycloudgallery.core.ai.runtime

/**
 * Le **20 immagini sintetiche** della misura breve del dispositivo (docs/fase6-progetto.md D15, §5.2): scene disegnate per
 * costruzione, senza nessun dato personale e senza file nell'APK (sono poche righe di aritmetica intera, gli stessi pixel su ogni
 * dispositivo: niente virgola mobile, niente caratteri, niente antialiasing). Servono a due cose:
 *  - [DeviceProbe] (`RealDeviceProbe`): quante immagini al secondo fa questo dispositivo, per la stima "Analizzare 18.000 media
 *    richiede circa 2 notti";
 *  - i vettori di riferimento (`core-ai/src/androidTest/assets/golden/`): le stesse 20 immagini danno, a ogni versione della libreria,
 *    vettori con coseno >= 0,99 con quelli salvati.
 *
 * Codice puro (nessuna classe di Android): provato sulla JVM per determinismo e distinzione; le immagini JPEG per i motori le fa
 * [ProbeImageSet].
 * Cambiare una scena cambia i vettori di riferimento: vanno rifatti (e [VERSION] sale).
 */
internal object ProbeImages {

  /** Sale a ogni cambio delle scene: i vettori di riferimento portano la versione con cui sono stati fatti. */
  const val VERSION: Int = 1

  const val COUNT: Int = 20
  const val WIDTH: Int = 768
  const val HEIGHT: Int = 576

  /** I pixel ARGB (riga per riga) della scena [index] (0 until [COUNT]). */
  fun pixels(index: Int): IntArray {
    require(index in 0 until COUNT) { "Scena fuori da 0..${COUNT - 1}" }
    val c = Scene(WIDTH, HEIGHT)
    when (index) {
      0 -> sunset(c)
      1 -> beach(c)
      2 -> starryNight(c)
      3 -> meadow(c)
      4 -> mountains(c)
      5 -> pinesInSnow(c)
      6 -> redDisk(c)
      7 -> blueOnYellow(c)
      8 -> checkerboard(c)
      9 -> rainbow(c)
      10 -> radial(c)
      11 -> city(c)
      12 -> tigerStripes(c)
      13 -> rings(c)
      14 -> mosaic(c)
      15 -> receipt(c)
      16 -> flowers(c)
      17 -> diagonalSplit(c)
      18 -> yellowTriangle(c)
      else -> hillsAndSun(c)
    }
    return c.px
  }

  // --- Le scene ---------------------------------------------------------------------------------------------------------------

  private fun sunset(c: Scene) {
    c.vGradient(0, c.h * 3 / 5, rgb(255, 140, 40), rgb(90, 30, 120))
    c.disk(c.w / 2, c.h * 3 / 5, 70, rgb(255, 235, 120))
    c.fillRect(0, c.h * 3 / 5, c.w, c.h, rgb(60, 35, 20))
  }

  private fun beach(c: Scene) {
    c.fillRect(0, 0, c.w, c.h * 2 / 5, rgb(120, 190, 240))
    c.vGradient(c.h * 2 / 5, c.h * 13 / 20, rgb(30, 110, 190), rgb(20, 70, 140))
    c.fillRect(0, c.h * 13 / 20, c.w, c.h, rgb(235, 210, 160))
    c.fillRect(0, c.h * 13 / 20 - 6, c.w, c.h * 13 / 20 + 4, rgb(250, 250, 250))
  }

  private fun starryNight(c: Scene) {
    c.vGradient(0, c.h, rgb(10, 15, 60), rgb(0, 0, 10))
    var seed = 12345
    repeat(140) {
      seed = seed * 1103515245 + 12345
      val x = (seed ushr 8) % c.w
      seed = seed * 1103515245 + 12345
      val y = (seed ushr 8) % c.h
      c.fillRect(x, y, x + 3, y + 3, rgb(255, 255, 255))
    }
    c.disk(c.w * 3 / 4, c.h / 4, 48, rgb(245, 245, 215))
  }

  private fun meadow(c: Scene) {
    c.fillRect(0, 0, c.w, c.h * 9 / 20, rgb(110, 180, 235))
    c.fillRect(0, c.h * 9 / 20, c.w, c.h, rgb(60, 150, 60))
    for (cx in intArrayOf(150, 190, 240, 520, 570)) c.disk(cx, if (cx < 400) 90 else 120, 38, rgb(250, 250, 250))
    var seed = 777
    repeat(36) {
      seed = seed * 1103515245 + 12345
      val x = (seed ushr 8) % c.w
      seed = seed * 1103515245 + 12345
      val y = c.h * 9 / 20 + 20 + (seed ushr 8) % (c.h * 11 / 20 - 30)
      c.disk(x, y, 5, rgb(250, 220, 40))
    }
  }

  private fun mountains(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(170, 205, 235))
    c.triangle(c.w / 4, c.h / 5, 0, c.h * 3 / 4, c.w * 3 / 5, c.h * 3 / 4, rgb(120, 125, 140))
    c.triangle(c.w * 11 / 20, c.h / 8, c.w / 5, c.h * 3 / 4, c.w, c.h * 3 / 4, rgb(95, 100, 120))
    c.triangle(c.w * 11 / 20, c.h / 8, c.w * 11 / 20 - 55, c.h / 8 + 80, c.w * 11 / 20 + 55, c.h / 8 + 80, rgb(250, 250, 255))
    c.fillRect(0, c.h * 3 / 4, c.w, c.h, rgb(55, 120, 60))
  }

  private fun pinesInSnow(c: Scene) {
    c.vGradient(0, c.h, rgb(235, 240, 250), rgb(205, 215, 235))
    for (i in 0 until 6) {
      val x = 70 + i * 120
      c.fillRect(x - 6, c.h - 130, x + 6, c.h - 90, rgb(90, 60, 40))
      c.triangle(x, c.h - 300 + (i % 2) * 40, x - 55, c.h - 100, x + 55, c.h - 100, rgb(30, 90, 60))
      c.triangle(x, c.h - 340 + (i % 2) * 40, x - 40, c.h - 200, x + 40, c.h - 200, rgb(40, 105, 70))
    }
  }

  private fun redDisk(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(250, 250, 250))
    c.disk(c.w / 2, c.h / 2, 190, rgb(215, 25, 30))
  }

  private fun blueOnYellow(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(250, 220, 40))
    c.fillRect(c.w / 4, c.h / 5, c.w * 3 / 4, c.h * 4 / 5, rgb(25, 55, 190))
  }

  private fun checkerboard(c: Scene) {
    val cell = 96
    for (y in 0 until c.h) for (x in 0 until c.w) c.px[y * c.w + x] = if (((x / cell) + (y / cell)) % 2 == 0) rgb(20, 20, 20) else rgb(240, 240, 240)
  }

  private fun rainbow(c: Scene) {
    val colors = intArrayOf(
      rgb(220, 30, 40), rgb(245, 130, 30), rgb(250, 220, 40), rgb(60, 170, 70), rgb(40, 130, 220), rgb(60, 60, 170), rgb(130, 60, 170),
    )
    for (y in 0 until c.h) for (x in 0 until c.w) c.px[y * c.w + x] = colors[y * colors.size / c.h]
  }

  private fun radial(c: Scene) {
    val cx = c.w / 2
    val cy = c.h / 2
    val max2 = cx * cx + cy * cy
    for (y in 0 until c.h) for (x in 0 until c.w) {
      val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
      val t = (d2 * 255L / max2).toInt().coerceIn(0, 255)
      c.px[y * c.w + x] = rgb(255 - t / 4, 240 - t * 220 / 255, 60 - t / 6)
    }
  }

  private fun city(c: Scene) {
    c.vGradient(0, c.h, rgb(190, 195, 205), rgb(150, 155, 170))
    val heights = intArrayOf(300, 220, 380, 260, 340, 200, 320, 250)
    val width = c.w / heights.size
    for ((i, h) in heights.withIndex()) {
      val x0 = i * width + 4
      val x1 = (i + 1) * width - 4
      c.fillRect(x0, c.h - h, x1, c.h, if (i % 2 == 0) rgb(110, 85, 70) else rgb(85, 90, 100))
      var wy = c.h - h + 18
      while (wy < c.h - 28) {
        var wx = x0 + 12
        while (wx + 14 < x1) {
          c.fillRect(wx, wy, wx + 14, wy + 18, if ((wx + wy) % 3 == 0) rgb(250, 225, 120) else rgb(50, 55, 70))
          wx += 28
        }
        wy += 40
      }
    }
  }

  private fun tigerStripes(c: Scene) {
    for (y in 0 until c.h) for (x in 0 until c.w) c.px[y * c.w + x] = if (((x + y) / 36) % 2 == 0) rgb(235, 120, 25) else rgb(20, 15, 10)
  }

  private fun rings(c: Scene) {
    val cx = c.w / 2
    val cy = c.h / 2
    for (y in 0 until c.h) for (x in 0 until c.w) {
      val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
      c.px[y * c.w + x] = if ((d2 / 5000) % 2 == 0) rgb(30, 160, 170) else rgb(250, 245, 235)
    }
  }

  private fun mosaic(c: Scene) {
    var seed = 424242
    val cell = 32
    for (by in 0 until (c.h + cell - 1) / cell) for (bx in 0 until (c.w + cell - 1) / cell) {
      seed = seed * 1103515245 + 12345
      val color = rgb((seed ushr 16) and 0xFF, (seed ushr 8) and 0xFF, seed and 0xFF)
      c.fillRect(bx * cell, by * cell, (bx + 1) * cell, (by + 1) * cell, color)
    }
  }

  private fun receipt(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(252, 252, 250))
    c.fillRect(c.w / 4, 40, c.w * 3 / 4, 62, rgb(20, 20, 20))
    var seed = 99
    var y = 100
    while (y < c.h - 40) {
      seed = seed * 1103515245 + 12345
      val lengthLeft = 120 + (seed ushr 8) % 260
      c.fillRect(70, y, 70 + lengthLeft, y + 12, rgb(35, 35, 35))
      c.fillRect(c.w - 160, y, c.w - 70, y + 12, rgb(35, 35, 35))
      y += 34
    }
    c.fillRect(70, c.h - 70, c.w - 70, c.h - 62, rgb(20, 20, 20))
  }

  private fun flowers(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(70, 140, 60))
    var seed = 31337
    val petals = intArrayOf(rgb(240, 120, 170), rgb(190, 90, 200), rgb(250, 225, 60), rgb(250, 250, 250))
    repeat(44) { i ->
      seed = seed * 1103515245 + 12345
      val x = 30 + (seed ushr 8) % (c.w - 60)
      seed = seed * 1103515245 + 12345
      val y = 30 + (seed ushr 8) % (c.h - 60)
      c.disk(x, y, 15, petals[i % petals.size])
      c.disk(x, y, 5, rgb(250, 190, 30))
    }
  }

  private fun diagonalSplit(c: Scene) {
    for (y in 0 until c.h) for (x in 0 until c.w) c.px[y * c.w + x] = if (x * c.h > y * c.w) rgb(20, 190, 210) else rgb(210, 40, 160)
  }

  private fun yellowTriangle(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(110, 15, 25))
    c.triangle(c.w / 2, 60, c.w / 5, c.h - 60, c.w * 4 / 5, c.h - 60, rgb(250, 215, 40))
  }

  private fun hillsAndSun(c: Scene) {
    c.fillRect(0, 0, c.w, c.h, rgb(135, 195, 245))
    c.disk(c.w * 4 / 5, c.h / 5, 60, rgb(255, 230, 90))
    for (x in 0 until c.w) {
      val dx = x - c.w / 3
      val top = c.h * 11 / 20 + dx * dx / 700
      c.fillRect(x, top.coerceIn(0, c.h), x + 1, c.h, rgb(70, 160, 70))
    }
    for (x in 0 until c.w) {
      val dx = x - c.w * 3 / 4
      val top = c.h * 13 / 20 + dx * dx / 900
      c.fillRect(x, top.coerceIn(0, c.h), x + 1, c.h, rgb(45, 120, 55))
    }
  }

  // --- Disegno con la sola aritmetica intera ----------------------------------------------------------------------------------

  private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

  private class Scene(val w: Int, val h: Int) {
    val px = IntArray(w * h)

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
      for (y in maxOf(y0, 0) until minOf(y1, h)) for (x in maxOf(x0, 0) until minOf(x1, w)) px[y * w + x] = color
    }

    fun disk(cx: Int, cy: Int, r: Int, color: Int) {
      val r2 = r * r
      for (y in maxOf(cy - r, 0)..minOf(cy + r, h - 1)) for (x in maxOf(cx - r, 0)..minOf(cx + r, w - 1)) {
        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r2) px[y * w + x] = color
      }
    }

    fun vGradient(y0: Int, y1: Int, from: Int, to: Int) {
      val span = maxOf(1, y1 - y0)
      for (y in maxOf(y0, 0) until minOf(y1, h)) {
        val t = (y - y0) * 256 / span
        val color = lerp(from, to, t)
        for (x in 0 until w) px[y * w + x] = color
      }
    }

    /** Triangolo pieno con i vertici in ordine qualunque (prova di segno dei bordi, interi). */
    fun triangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
      val minX = maxOf(minOf(x0, x1, x2), 0)
      val maxX = minOf(maxOf(x0, x1, x2), w - 1)
      val minY = maxOf(minOf(y0, y1, y2), 0)
      val maxY = minOf(maxOf(y0, y1, y2), h - 1)
      for (y in minY..maxY) for (x in minX..maxX) {
        val d0 = (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0)
        val d1 = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
        val d2 = (x0 - x2) * (y - y2) - (y0 - y2) * (x - x2)
        val allNonNegative = d0 >= 0 && d1 >= 0 && d2 >= 0
        val allNonPositive = d0 <= 0 && d1 <= 0 && d2 <= 0
        if (allNonNegative || allNonPositive) px[y * w + x] = color
      }
    }

    private fun lerp(a: Int, b: Int, t256: Int): Int {
      fun ch(shift: Int): Int {
        val ca = (a shr shift) and 0xFF
        val cb = (b shr shift) and 0xFF
        return ca + (cb - ca) * t256 / 256
      }
      return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
  }
}
