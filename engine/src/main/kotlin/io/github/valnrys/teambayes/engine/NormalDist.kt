/*
 * erfc() below is a Kotlin translation of erfc() from s_erf.c in netlib's
 * fdlibm (https://netlib.org/fdlibm/s_erf.c), which carries this notice:
 *
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice 
 * is preserved.
 * ====================================================
 *
 * cdf(), pdf() and invCdf() below follow CPython 3.14's Lib/statistics.py
 * (NormalDist.cdf, NormalDist.pdf and _normal_dist_inv_cdf), Copyright (c) 2001
 * Python Software Foundation; All Rights Reserved, used under the PSF License
 * Agreement version 2. Changes: translated to Kotlin, with mu fixed at 0 and
 * sigma at 1.
 *
 * See NOTICE for both licences in full.
 */
package io.github.valnrys.teambayes.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The standard normal distribution (mean 0, standard deviation 1).
 *
 * The rating model leans on three functions of the bell curve: its density ([pdf]),
 * the area under it to the left of a point ([cdf]), and the inverse of that area
 * ([invCdf]). The reference implementation (openskill on Python 3.14) gets these from
 * Python's `statistics.NormalDist`, so each function here follows the same formulas
 * in the same order. That keeps us within a few units in the last place (ulp) of
 * Python, far inside the 1e-9 test tolerance. We are not bit-identical: on macOS,
 * Python's `math.erfc` is Apple's libm and its inverse CDF is CPython's C version,
 * while we use fdlibm's erfc and a Kotlin port. Closeness matters because the model
 * compares these values against small thresholds to choose between formulas.
 *
 * `exp` and `log` come from [StrictMath], which is fdlibm and gives the same bits on
 * every JVM and Android device.
 */
internal object NormalDist {

    private val SQRT_2 = sqrt(2.0)

    /**
     * sqrt(2π), the pdf's normalising constant. Python's `statistics` module writes
     * `sqrt(tau)`, where its `tau` is 2π (unrelated to the model's tau). `sqrt` is
     * correctly rounded, so computing it once gives the same bits as every time.
     */
    private val SQRT_TWO_PI = sqrt(2.0 * PI)

    /**
     * Cumulative distribution function: the probability that a standard normal
     * variable is at most [x].
     *
     * Written as Python 3.14 writes it, `0.5 * erfc((mu - x) / (sigma * sqrt(2)))`
     * with mu = 0 and sigma = 1. Going through [erfc] keeps full relative accuracy
     * far into the lower tail, where `cdf(-10)` is about 7.6e-24.
     */
    fun cdf(x: Double): Double = 0.5 * erfc(-x / SQRT_2)

    /**
     * Probability density function: the height of the bell curve at [x].
     *
     * Depends only on `x * x`, so `pdf(x) == pdf(-x)` holds exactly. openskill relies
     * on that symmetry: one of its helpers computes `pdf(-t) - pdf(t)` and must get 0.0.
     */
    fun pdf(x: Double): Double = StrictMath.exp(x * x / -2.0) / SQRT_TWO_PI

    /**
     * Inverse of [cdf]: the point `x` with `cdf(x) == p`, for `0 < p < 1`.
     *
     * A port of CPython's `_normal_dist_inv_cdf`, which uses Wichura's algorithm AS241
     * (Applied Statistics 37(3), 1988): a rational approximation in the middle of the
     * range and two more in the tails. Accurate to about 1e-16 relative.
     */
    fun invCdf(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p must be strictly between 0 and 1, was $p" }
        val q = p - 0.5

        if (abs(q) <= 0.425) {
            val r = 0.180625 - q * q
            val num = (((((((2.5090809287301226727e+3 * r +
                3.3430575583588128105e+4) * r +
                6.7265770927008700853e+4) * r +
                4.5921953931549871457e+4) * r +
                1.3731693765509461125e+4) * r +
                1.9715909503065514427e+3) * r +
                1.3314166789178437745e+2) * r +
                3.3871328727963666080e+0) * q
            val den = (((((((5.2264952788528545610e+3 * r +
                2.8729085735721942674e+4) * r +
                3.9307895800092710610e+4) * r +
                2.1213794301586595867e+4) * r +
                5.3941960214247511077e+3) * r +
                6.8718700749205790830e+2) * r +
                4.2313330701600911252e+1) * r +
                1.0)
            return num / den
        }

        var r = if (q <= 0.0) p else 1.0 - p
        r = sqrt(-StrictMath.log(r))
        val num: Double
        val den: Double
        if (r <= 5.0) {
            r -= 1.6
            num = (((((((7.74545014278341407640e-4 * r +
                2.27238449892691845833e-2) * r +
                2.41780725177450611770e-1) * r +
                1.27045825245236838258e+0) * r +
                3.64784832476320460504e+0) * r +
                5.76949722146069140550e+0) * r +
                4.63033784615654529590e+0) * r +
                1.42343711074968357734e+0)
            den = (((((((1.05075007164441684324e-9 * r +
                5.47593808499534494600e-4) * r +
                1.51986665636164571966e-2) * r +
                1.48103976427480074590e-1) * r +
                6.89767334985100004550e-1) * r +
                1.67638483018380384940e+0) * r +
                2.05319162663775882187e+0) * r +
                1.0)
        } else {
            r -= 5.0
            num = (((((((2.01033439929228813265e-7 * r +
                2.71155556874348757815e-5) * r +
                1.24266094738807843860e-3) * r +
                2.65321895265761230930e-2) * r +
                2.96560571828504891230e-1) * r +
                1.78482653991729133580e+0) * r +
                5.46378491116411436990e+0) * r +
                6.65790464350110377720e+0)
            den = (((((((2.04426310338993978564e-15 * r +
                1.42151175831644588870e-7) * r +
                1.84631831751005468180e-5) * r +
                7.86869131145613259100e-4) * r +
                1.48753612908506148525e-2) * r +
                1.36929880922735805310e-1) * r +
                5.99832206555887937690e-1) * r +
                1.0)
        }
        val x = num / den
        return if (q < 0.0) -x else x
    }

    /**
     * Complementary error function, `erfc(x) = 1 - erf(x)`, accurate to within a few
     * units in the last place.
     *
     * A faithful port of netlib fdlibm's `erfc`, the ancestor of the erfc in most C
     * maths libraries. It splits the real line into
     * five ranges and uses a tuned rational approximation in each. The branches test
     * the high 32 bits of the double directly, exactly as the C code does, so the
     * range boundaries land on the same bit patterns.
     */
    fun erfc(x: Double): Double {
        val bits = x.toRawBits()
        val hx = (bits ushr 32).toInt() // high word, sign bit included (C's `int hx`)
        val ix = hx and 0x7fffffff // high word of |x|

        if (ix >= 0x7ff00000) {
            // erfc(NaN) = NaN, erfc(+inf) = 0, erfc(-inf) = 2
            return ((hx ushr 31) shl 1).toDouble() + ONE / x
        }

        if (ix < 0x3feb0000) { // |x| < 0.84375
            if (ix < 0x3c700000) return ONE - x // |x| < 2**-56
            val z = x * x
            val r = PP0 + z * (PP1 + z * (PP2 + z * (PP3 + z * PP4)))
            val s = ONE + z * (QQ1 + z * (QQ2 + z * (QQ3 + z * (QQ4 + z * QQ5))))
            val y = r / s
            return if (hx < 0x3fd00000) { // x < 1/4 (including every negative x)
                ONE - (x + x * y)
            } else {
                var t = x * y
                t += (x - HALF)
                HALF - t
            }
        }

        if (ix < 0x3ff40000) { // 0.84375 <= |x| < 1.25
            val s = abs(x) - ONE
            val p = PA0 + s * (PA1 + s * (PA2 + s * (PA3 + s * (PA4 + s * (PA5 + s * PA6)))))
            val q = ONE + s * (QA1 + s * (QA2 + s * (QA3 + s * (QA4 + s * (QA5 + s * QA6)))))
            return if (hx >= 0) {
                val z = ONE - ERX
                z - p / q
            } else {
                val z = ERX + p / q
                ONE + z
            }
        }

        if (ix < 0x403c0000) { // |x| < 28
            val ax = abs(x)
            val s = ONE / (ax * ax)
            val bigR: Double
            val bigS: Double
            if (ix < 0x4006DB6D) { // |x| < 1/0.35, about 2.857143
                bigR = RA0 + s * (RA1 + s * (RA2 + s * (RA3 + s * (RA4 + s * (
                    RA5 + s * (RA6 + s * RA7))))))
                bigS = ONE + s * (SA1 + s * (SA2 + s * (SA3 + s * (SA4 + s * (
                    SA5 + s * (SA6 + s * (SA7 + s * SA8)))))))
            } else { // |x| >= 1/0.35
                if (hx < 0 && ix >= 0x40180000) return TWO - TINY // x <= -6
                bigR = RB0 + s * (RB1 + s * (RB2 + s * (RB3 + s * (RB4 + s * (
                    RB5 + s * RB6)))))
                bigS = ONE + s * (SB1 + s * (SB2 + s * (SB3 + s * (SB4 + s * (
                    SB5 + s * (SB6 + s * SB7))))))
            }
            // z is |x| with the low 32 bits cleared. Splitting x into z + (x - z) lets
            // exp(-x*x) be computed as exp(-z*z) * exp(small), without losing digits.
            val z = Double.fromBits(ax.toRawBits() and HIGH_WORD_MASK)
            val r = StrictMath.exp(-z * z - 0.5625) *
                StrictMath.exp((z - ax) * (z + ax) + bigR / bigS)
            return if (hx > 0) r / ax else TWO - r / ax
        }

        // |x| >= 28: erfc underflows to 0 (x > 0) or rounds to 2 (x < 0).
        return if (hx > 0) TINY * TINY else TWO - TINY
    }

    // fdlibm s_erf.c constants, with Sun's hex bit patterns for checking.
    private const val HIGH_WORD_MASK = 0xFFFFFFFFL shl 32
    private const val TINY = 1e-300
    private const val HALF = 5.00000000000000000000e-01
    private const val ONE = 1.00000000000000000000e+00
    private const val TWO = 2.00000000000000000000e+00

    /** erf(1) rounded to float precision: (float) 0.84506291151. */
    private const val ERX = 8.45062911510467529297e-01 // 0x3FEB0AC1, 0x60000000

    // Coefficients for the approximation to erf on [0, 0.84375].
    private const val PP0 = 1.28379167095512558561e-01 // 0x3FC06EBA, 0x8214DB68
    private const val PP1 = -3.25042107247001499370e-01 // 0xBFD4CD7D, 0x691CB913
    private const val PP2 = -2.84817495755985104766e-02 // 0xBF9D2A51, 0xDBD7194F
    private const val PP3 = -5.77027029648944159157e-03 // 0xBF77A291, 0x236668E4
    private const val PP4 = -2.37630166566501626084e-05 // 0xBEF8EAD6, 0x120016AC
    private const val QQ1 = 3.97917223959155352819e-01 // 0x3FD97779, 0xCDDADC09
    private const val QQ2 = 6.50222499887672944485e-02 // 0x3FB0A54C, 0x5536CEBA
    private const val QQ3 = 5.08130628187576562776e-03 // 0x3F74D022, 0xC4D36B0F
    private const val QQ4 = 1.32494738004321644526e-04 // 0x3F215DC9, 0x221C1A10
    private const val QQ5 = -3.96022827877536812320e-06 // 0xBED09C43, 0x42A26120

    // Coefficients for the approximation to erf on [0.84375, 1.25].
    private const val PA0 = -2.36211856075265944077e-03 // 0xBF6359B8, 0xBEF77538
    private const val PA1 = 4.14856118683748331666e-01 // 0x3FDA8D00, 0xAD92B34D
    private const val PA2 = -3.72207876035701323847e-01 // 0xBFD7D240, 0xFBB8C3F1
    private const val PA3 = 3.18346619901161753674e-01 // 0x3FD45FCA, 0x805120E4
    private const val PA4 = -1.10894694282396677476e-01 // 0xBFBC6398, 0x3D3E28EC
    private const val PA5 = 3.54783043256182359371e-02 // 0x3FA22A36, 0x599795EB
    private const val PA6 = -2.16637559486879084300e-03 // 0xBF61BF38, 0x0A96073F
    private const val QA1 = 1.06420880400844228286e-01 // 0x3FBB3E66, 0x18EEE323
    private const val QA2 = 5.40397917702171048937e-01 // 0x3FE14AF0, 0x92EB6F33
    private const val QA3 = 7.18286544141962662868e-02 // 0x3FB2635C, 0xD99FE9A7
    private const val QA4 = 1.26171219808761642112e-01 // 0x3FC02660, 0xE763351F
    private const val QA5 = 1.36370839120290507362e-02 // 0x3F8BEDC2, 0x6B51DD1C
    private const val QA6 = 1.19844998467991074170e-02 // 0x3F888B54, 0x5735151D

    // Coefficients for the approximation to erfc on [1.25, 1/0.35].
    private const val RA0 = -9.86494403484714822705e-03 // 0xBF843412, 0x600D6435
    private const val RA1 = -6.93858572707181764372e-01 // 0xBFE63416, 0xE4BA7360
    private const val RA2 = -1.05586262253232909814e+01 // 0xC0251E04, 0x41B0E726
    private const val RA3 = -6.23753324503260060396e+01 // 0xC04F300A, 0xE4CBA38D
    private const val RA4 = -1.62396669462573470355e+02 // 0xC0644CB1, 0x84282266
    private const val RA5 = -1.84605092906711035994e+02 // 0xC067135C, 0xEBCCABB2
    private const val RA6 = -8.12874355063065934246e+01 // 0xC0545265, 0x57E4D2F2
    private const val RA7 = -9.81432934416914548592e+00 // 0xC023A0EF, 0xC69AC25C
    private const val SA1 = 1.96512716674392571292e+01 // 0x4033A6B9, 0xBD707687
    private const val SA2 = 1.37657754143519042600e+02 // 0x4061350C, 0x526AE721
    private const val SA3 = 4.34565877475229228821e+02 // 0x407B290D, 0xD58A1A71
    private const val SA4 = 6.45387271733267880336e+02 // 0x40842B19, 0x21EC2868
    private const val SA5 = 4.29008140027567833386e+02 // 0x407AD021, 0x57700314
    private const val SA6 = 1.08635005541779435134e+02 // 0x405B28A3, 0xEE48AE2C
    private const val SA7 = 6.57024977031928170135e+00 // 0x401A47EF, 0x8E484A93
    private const val SA8 = -6.04244152148580987438e-02 // 0xBFAEEFF2, 0xEE749A62

    // Coefficients for the approximation to erfc on [1/0.35, 28].
    private const val RB0 = -9.86494292470009928597e-03 // 0xBF843412, 0x39E86F4A
    private const val RB1 = -7.99283237680523006574e-01 // 0xBFE993BA, 0x70C285DE
    private const val RB2 = -1.77579549177547519889e+01 // 0xC031C209, 0x555F995A
    private const val RB3 = -1.60636384855821916062e+02 // 0xC064145D, 0x43C5ED98
    private const val RB4 = -6.37566443368389627722e+02 // 0xC083EC88, 0x1375F228
    private const val RB5 = -1.02509513161107724954e+03 // 0xC0900461, 0x6A2E5992
    private const val RB6 = -4.83519191608651397019e+02 // 0xC07E384E, 0x9BDC383F
    private const val SB1 = 3.03380607434824582924e+01 // 0x403E568B, 0x261D5190
    private const val SB2 = 3.25792512996573918826e+02 // 0x40745CAE, 0x221B9F0A
    private const val SB3 = 1.53672958608443695994e+03 // 0x409802EB, 0x189D5118
    private const val SB4 = 3.19985821950859553908e+03 // 0x40A8FFB7, 0x688C246A
    private const val SB5 = 2.55305040643316442583e+03 // 0x40A3F219, 0xCEDF3BE6
    private const val SB6 = 4.74528541206955367215e+02 // 0x407DA874, 0xE79FE763
    private const val SB7 = -2.24409524465858183362e+01 // 0xC03670E2, 0x42712D62
}
