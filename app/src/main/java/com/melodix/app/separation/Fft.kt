package com.melodix.app.separation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Комплексное БПФ произвольного размера:
 *  • 2^p — классический радикс-2;
 *  • 3·2^p — быстрый смешанный радикс (n_fft = 6144 у моделей MDX-Net);
 *  • остальные (например 7680) — алгоритм Блюстейна.
 * Без масштабирования: inverse() не делит на n.
 */
class Fft(val n: Int) {
    private val isPow2 = n >= 2 && (n and (n - 1)) == 0
    private val radix3 = !isPow2 && n % 3 == 0 && ((n / 3) and (n / 3 - 1)) == 0 && n / 3 >= 2
    private val bluestein = !isPow2 && !radix3
    private val m = when { radix3 -> n / 3; isPow2 -> n; else -> 2 }
    private val cosM = DoubleArray(m / 2) { cos(2 * PI * it / m) }
    private val sinM = DoubleArray(m / 2) { sin(2 * PI * it / m) }
    private val rev = IntArray(m)
    private val cosN = if (radix3) DoubleArray(n) { cos(2 * PI * it / n) } else DoubleArray(0)
    private val sinN = if (radix3) DoubleArray(n) { sin(2 * PI * it / n) } else DoubleArray(0)
    private val tr = if (radix3) Array(3) { DoubleArray(m) } else emptyArray()
    private val ti = if (radix3) Array(3) { DoubleArray(m) } else emptyArray()

    // ---- Блюстейн ----
    private val bM: Int = if (bluestein) { var x = 1; while (x < 2 * n - 1) x = x shl 1; x } else 0
    private val inner: Fft? = if (bluestein) Fft(bM) else null
    private val chirpR = if (bluestein) DoubleArray(n) { val a = PI * ((it.toLong() * it) % (2L * n)) / n; cos(a) } else DoubleArray(0)
    private val chirpI = if (bluestein) DoubleArray(n) { val a = PI * ((it.toLong() * it) % (2L * n)) / n; sin(a) } else DoubleArray(0)
    private val kernFwdR = DoubleArray(bM); private val kernFwdI = DoubleArray(bM)
    private val kernInvR = DoubleArray(bM); private val kernInvI = DoubleArray(bM)
    private val wr = DoubleArray(bM); private val wi = DoubleArray(bM)

    init {
        var bits = 0
        while ((1 shl bits) < m) bits++
        for (i in 0 until m) {
            var r = 0; var x = i
            for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
        }
        if (bluestein) {
            // Ядро b[k] = exp(+-i*pi*k^2/n) (сопряжённый чирп), симметрично продолженное
            for (sgn in 0..1) {
                val kr = if (sgn == 0) kernFwdR else kernInvR
                val ki = if (sgn == 0) kernFwdI else kernInvI
                val s = if (sgn == 0) 1.0 else -1.0
                for (k in 0 until n) {
                    kr[k] = chirpR[k]; ki[k] = s * chirpI[k]
                    if (k > 0) { kr[bM - k] = chirpR[k]; ki[bM - k] = s * chirpI[k] }
                }
                inner!!.forward(kr, ki)
            }
        }
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)
    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, 1.0)

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        if (bluestein) { blue(re, im, sign); return }
        if (!radix3) { pow2(re, im, sign); return }
        for (r in 0 until 3) {
            val a = tr[r]; val b = ti[r]
            for (j in 0 until m) { a[j] = re[3 * j + r]; b[j] = im[3 * j + r] }
            pow2(a, b, sign)
        }
        for (k in 0 until n) {
            val km = k % m
            var sr = tr[0][km]; var si = ti[0][km]
            for (r in 1..2) {
                val idx = (r * k) % n
                val wr = cosN[idx]; val wi = sign * sinN[idx]
                val xr = tr[r][km]; val xi = ti[r][km]
                sr += xr * wr - xi * wi
                si += xr * wi + xi * wr
            }
            re[k] = sr; im[k] = si
        }
    }

    private fun blue(re: DoubleArray, im: DoubleArray, sign: Double) {
        val f = inner!!
        // a[k] = x[k] * exp(sign*i*pi*k^2/n)
        wr.fill(0.0); wi.fill(0.0)
        for (k in 0 until n) {
            val cr = chirpR[k]; val ci = sign * chirpI[k]
            wr[k] = re[k] * cr - im[k] * ci
            wi[k] = re[k] * ci + im[k] * cr
        }
        f.forward(wr, wi)
        val kr = if (sign < 0) kernFwdR else kernInvR
        val ki = if (sign < 0) kernFwdI else kernInvI
        for (k in 0 until bM) {
            val a = wr[k] * kr[k] - wi[k] * ki[k]
            val b = wr[k] * ki[k] + wi[k] * kr[k]
            wr[k] = a; wi[k] = b
        }
        f.inverse(wr, wi)
        for (k in 0 until n) {
            val xr = wr[k] / bM; val xi = wi[k] / bM
            val cr = chirpR[k]; val ci = sign * chirpI[k]
            re[k] = xr * cr - xi * ci
            im[k] = xr * ci + xi * cr
        }
    }

    private fun pow2(re: DoubleArray, im: DoubleArray, sign: Double) {
        for (i in 0 until m) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= m) {
            val half = size / 2
            val step = m / size
            var i = 0
            while (i < m) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosM[k]; val wi = sign * sinM[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr; im[l] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * Накопитель overlap-add с потоковой выдачей готовых сэмплов:
 * всё, что левее начала очередного блока, уже окончательно и отдаётся в sink.
 */
class OverlapAccumulator(
    private val capacity: Int,
    private val total: Long,
    startBase: Long,
    private val sink: (startIndex: Long, l: FloatArray, r: FloatArray, count: Int) -> Unit,
) {
    private val accL = FloatArray(capacity)
    private val accR = FloatArray(capacity)
    private val wsum = FloatArray(capacity)
    private val outL = FloatArray(capacity)
    private val outR = FloatArray(capacity)
    private var base = startBase

    /** dataL/dataR уже умножены на окно; weights — вклад в нормирующую сумму. */
    fun add(start: Long, dataL: FloatArray, dataR: FloatArray, weights: FloatArray, len: Int) {
        require(start >= base)
        while (start > base) flush((start - base).coerceAtMost(capacity.toLong()).toInt())
        for (i in 0 until len) {
            accL[i] += dataL[i]; accR[i] += dataR[i]; wsum[i] += weights[i]
        }
    }

    private fun flush(count: Int) {
        val c = count.coerceAtMost(capacity)
        var emitStart = -1L
        var n = 0
        for (i in 0 until c) {
            val idx = base + i
            if (idx < 0 || idx >= total) continue
            if (emitStart < 0) emitStart = idx
            val w = if (wsum[i] > 1e-8f) wsum[i] else 1f
            outL[n] = accL[i] / w; outR[n] = accR[i] / w
            n++
        }
        if (n > 0) sink(emitStart, outL, outR, n)
        System.arraycopy(accL, c, accL, 0, capacity - c); accL.fill(0f, capacity - c, capacity)
        System.arraycopy(accR, c, accR, 0, capacity - c); accR.fill(0f, capacity - c, capacity)
        System.arraycopy(wsum, c, wsum, 0, capacity - c); wsum.fill(0f, capacity - c, capacity)
        base += c
    }

    fun finish() {
        while (base < total) flush(capacity)
    }
}

/**
 * STFT/ISTFT в точности как в UVR MDX-Net (torch.stft/istft, center=True, reflect, periodic Hann).
 * Раскладка тензора: [L_re, L_im, R_re, R_im] × dimF × dimT.
 * Два вещественных канала упакованы в одно комплексное БПФ (L + iR) — вдвое быстрее.
 */
class MdxStft(val nFft: Int, val hop: Int, val dimF: Int, val dimT: Int) {
    val chunk = hop * (dimT - 1)
    private val fft = Fft(nFft)
    private val w = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) }
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val olaL = DoubleArray(chunk + nFft)
    private val olaR = DoubleArray(chunk + nFft)
    private val env = DoubleArray(chunk + nFft)
    private val plane = dimF * dimT

    fun forward(l: FloatArray, r: FloatArray, out: FloatArray) {
        val half = nFft / 2
        val len = chunk
        for (t in 0 until dimT) {
            val start = t * hop - half
            for (n in 0 until nFft) {
                var idx = start + n
                if (idx < 0) idx = -idx
                if (idx >= len) idx = 2 * (len - 1) - idx
                re[n] = l[idx] * w[n]; im[n] = r[idx] * w[n]
            }
            fft.forward(re, im)
            for (f in 0 until dimF) {
                val a = re[f]; val b = im[f]
                val nf = if (f == 0) 0 else nFft - f
                val c = re[nf]; val d = im[nf]
                val o = f * dimT + t
                out[o] = ((a + c) * 0.5).toFloat()               // L re
                out[plane + o] = ((b - d) * 0.5).toFloat()       // L im
                out[2 * plane + o] = ((b + d) * 0.5).toFloat()   // R re
                out[3 * plane + o] = ((c - a) * 0.5).toFloat()   // R im
            }
        }
    }

    fun inverse(spec: FloatArray, outL: FloatArray, outR: FloatArray) {
        val half = nFft / 2
        olaL.fill(0.0); olaR.fill(0.0); env.fill(0.0)
        for (t in 0 until dimT) {
            for (k in 0 until nFft) {
                if (k <= half) {
                    if (k < dimF) {
                        val o = k * dimT + t
                        val lr = spec[o]; val li = spec[plane + o]; val rr = spec[2 * plane + o]; val ri = spec[3 * plane + o]
                        re[k] = (lr - ri).toDouble(); im[k] = (li + rr).toDouble()
                    } else { re[k] = 0.0; im[k] = 0.0 }
                } else {
                    val kk = nFft - k
                    if (kk < dimF) {
                        val o = kk * dimT + t
                        val lr = spec[o]; val li = spec[plane + o]; val rr = spec[2 * plane + o]; val ri = spec[3 * plane + o]
                        re[k] = (lr + ri).toDouble(); im[k] = (rr - li).toDouble()
                    } else { re[k] = 0.0; im[k] = 0.0 }
                }
            }
            fft.inverse(re, im)
            val pos = t * hop
            for (n in 0 until nFft) {
                val wn = w[n]
                olaL[pos + n] += re[n] / nFft * wn
                olaR[pos + n] += im[n] / nFft * wn
                env[pos + n] += wn * wn
            }
        }
        for (j in 0 until chunk) {
            val e = env[j + half]
            val g = if (e > 1e-10) 1.0 / e else 0.0
            outL[j] = (olaL[j + half] * g).toFloat()
            outR[j] = (olaR[j + half] * g).toFloat()
        }
    }
}
