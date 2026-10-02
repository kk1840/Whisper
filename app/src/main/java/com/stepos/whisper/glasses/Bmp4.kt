package com.stepos.whisper.glasses

import android.graphics.Bitmap

/**
 * G2 固件唯一会绘制的图片格式：标准 Windows BMP，4 bit/像素，自底向上，行按 4 字节对齐，
 * 16 级灰度调色板（第 i 项 = (i*17, i*17, i*17)），像素数据偏移 14 + 40 + 64 = 118。
 * 0 = 不亮，15 = 最亮。
 */
object Bmp4 {
    const val PIXEL_OFFSET = 14 + 40 + 64

    /** 生成一张 [width]x[height] 的 4-bpp BMP，[pixel] 返回 0..15。 */
    fun build(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): ByteArray {
        val bytesPerPixelRow = (width + 1) / 2
        val rowStride = (bytesPerPixelRow + 3) and 3.inv()
        val pixelDataSize = rowStride * height
        val fileSize = PIXEL_OFFSET + pixelDataSize
        val b = ByteArray(fileSize)
        fun u16(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun u32(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        // BITMAPFILEHEADER
        b[0] = 'B'.code.toByte(); b[1] = 'M'.code.toByte()
        u32(2, fileSize); u32(10, PIXEL_OFFSET)
        // BITMAPINFOHEADER
        u32(14, 40); u32(18, width); u32(22, height); u16(26, 1); u16(28, 4)
        u32(34, pixelDataSize); u32(46, 16) // biClrUsed = 16
        // 调色板 BGRA
        for (i in 0 until 16) {
            val v = i * 17
            val p = 54 + i * 4
            b[p] = v.toByte(); b[p + 1] = v.toByte(); b[p + 2] = v.toByte()
        }
        // 像素：自底向上，高 nibble 在前
        for (bmpRow in 0 until height) {
            val srcY = height - 1 - bmpRow
            val rowOff = PIXEL_OFFSET + bmpRow * rowStride
            var x = 0
            while (x < width) {
                val hi = pixel(x, srcY) and 0x0F
                val lo = (if (x + 1 < width) pixel(x + 1, srcY) else 0) and 0x0F
                b[rowOff + (x shr 1)] = ((hi shl 4) or lo).toByte()
                x += 2
            }
        }
        return b
    }

    /** ARGB 像素按 Rec.601 亮度压到 0..15。 */
    fun gray4(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val bl = argb and 0xFF
        val luma = (r * 77 + g * 150 + bl * 29) shr 8
        return luma shr 4
    }

    /** 一块瓦片：在整幅画面里的位置与它的 BMP 字节。 */
    class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val bmp: ByteArray)

    /**
     * 把 [src]（必须正好是 cols*tileW × rows*tileH）按阅读顺序切成 cols×rows 块瓦片，各自编码成 4-bpp BMP。
     * 固件单张图上限约 20 KB，所以整屏要切块（288×144 的一块约 20.8 KB，288×128 约 18.5 KB）。
     */
    fun tiles(src: Bitmap, tileW: Int, tileH: Int, cols: Int, rows: Int): List<Tile> {
        val fullW = tileW * cols
        val fullH = tileH * rows
        require(src.width == fullW && src.height == fullH) {
            "bitmap ${src.width}x${src.height} does not match ${fullW}x${fullH}"
        }
        val px = IntArray(fullW * fullH)
        src.getPixels(px, 0, fullW, 0, 0, fullW, fullH)
        val out = ArrayList<Tile>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val ox = c * tileW
                val oy = r * tileH
                val bmp = build(tileW, tileH) { lx, ly -> gray4(px[(oy + ly) * fullW + ox + lx]) }
                out.add(Tile(ox, oy, tileW, tileH, bmp))
            }
        }
        return out
    }

    /** 全黑瓦片（预热用：CREATE 之后的第一次图片流会被固件丢掉）。 */
    fun black(tileW: Int, tileH: Int): ByteArray = build(tileW, tileH) { _, _ -> 0 }
}
