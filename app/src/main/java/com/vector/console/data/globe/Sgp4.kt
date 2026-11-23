package com.vector.console.data.globe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SGP4 / SDP4 orbit propagation, ported from satellite.js 6.0.1 (MIT,
 * © Shashwat Kandadai / Dinamicka), which is itself a port of David Vallado's
 * reference implementation of the 2006 AIAA paper.
 *
 * Deliberately a literal transliteration: every local keeps the name it has in
 * the reference so a divergence can be found by diffing against the source. Do
 * not "clean up" the maths. `Sgp4Test` pins the output against golden vectors
 * generated from satellite.js, so a refactor that changes a result fails.
 *
 * Pure Kotlin, no Android imports — runs on the JVM unit-test classpath.
 */

private const val PI = Math.PI
private const val TWO_PI = PI * 2
private const val DEG2RAD = PI / 180.0
private const val RAD2DEG = 180.0 / PI

// WGS-72, the set the TLE catalogue is fitted against.
private const val MU = 398600.8
private const val EARTH_RADIUS = 6378.135
private val XKE = 60.0 / sqrt(EARTH_RADIUS * EARTH_RADIUS * EARTH_RADIUS / MU)
private val VKMPERSEC = EARTH_RADIUS * XKE / 60.0
private val TUMIN = 1.0 / XKE
private const val J2 = 0.001082616
private const val J3 = -0.00000253881
private const val J4 = -0.00000165597
private const val J3OJ2 = J3 / J2
private const val X2O3 = 2.0 / 3.0
private val XPDOTP = 1440.0 / (2.0 * PI)

/** A position or velocity triple, km or km/s. */
data class Eci(val x: Double, val y: Double, val z: Double)

/** Result of one propagation. [meanElements] are the SGP4 mean elements. */
data class EciState(val position: Eci, val velocity: Eci)

/** Geodetic position. [height] is km above the WGS-84 ellipsoid. */
data class Geodetic(val latitude: Double, val longitude: Double, val height: Double)

/**
 * Propagator state for one satellite. Field names match the reference
 * implementation exactly; the near-earth and deep-space blocks are zeroed by
 * [Sgp4.twoline2satrec] before use.
 */
class SatRec {
    var error: Int = 0
    var satnum: String = ""
    var epochyr: Int = 0
    var epochdays: Double = 0.0
    var ndot: Double = 0.0
    var nddot: Double = 0.0
    var bstar: Double = 0.0
    var inclo: Double = 0.0
    var nodeo: Double = 0.0
    var ecco: Double = 0.0
    var argpo: Double = 0.0
    var mo: Double = 0.0
    var no: Double = 0.0
    var jdsatepoch: Double = 0.0

    var isimp: Int = 0
    var method: Char = 'n'
    var aycof: Double = 0.0
    var con41: Double = 0.0
    var cc1: Double = 0.0
    var cc4: Double = 0.0
    var cc5: Double = 0.0
    var d2: Double = 0.0
    var d3: Double = 0.0
    var d4: Double = 0.0
    var delmo: Double = 0.0
    var eta: Double = 0.0
    var argpdot: Double = 0.0
    var omgcof: Double = 0.0
    var sinmao: Double = 0.0
    var t: Double = 0.0
    var t2cof: Double = 0.0
    var t3cof: Double = 0.0
    var t4cof: Double = 0.0
    var t5cof: Double = 0.0
    var x1mth2: Double = 0.0
    var x7thm1: Double = 0.0
    var mdot: Double = 0.0
    var nodedot: Double = 0.0
    var xlcof: Double = 0.0
    var xmcof: Double = 0.0
    var nodecf: Double = 0.0

    var irez: Int = 0
    var d2201: Double = 0.0
    var d2211: Double = 0.0
    var d3210: Double = 0.0
    var d3222: Double = 0.0
    var d4410: Double = 0.0
    var d4422: Double = 0.0
    var d5220: Double = 0.0
    var d5232: Double = 0.0
    var d5421: Double = 0.0
    var d5433: Double = 0.0
    var dedt: Double = 0.0
    var del1: Double = 0.0
    var del2: Double = 0.0
    var del3: Double = 0.0
    var didt: Double = 0.0
    var dmdt: Double = 0.0
    var dnodt: Double = 0.0
    var domdt: Double = 0.0
    var e3: Double = 0.0
    var ee2: Double = 0.0
    var peo: Double = 0.0
    var pgho: Double = 0.0
    var pho: Double = 0.0
    var pinco: Double = 0.0
    var plo: Double = 0.0
    var se2: Double = 0.0
    var se3: Double = 0.0
    var sgh2: Double = 0.0
    var sgh3: Double = 0.0
    var sgh4: Double = 0.0
    var sh2: Double = 0.0
    var sh3: Double = 0.0
    var si2: Double = 0.0
    var si3: Double = 0.0
    var sl2: Double = 0.0
    var sl3: Double = 0.0
    var sl4: Double = 0.0
    var gsto: Double = 0.0
    var xfact: Double = 0.0
    var xgh2: Double = 0.0
    var xgh3: Double = 0.0
    var xgh4: Double = 0.0
    var xh2: Double = 0.0
    var xh3: Double = 0.0
    var xi2: Double = 0.0
    var xi3: Double = 0.0
    var xl2: Double = 0.0
    var xl3: Double = 0.0
    var xl4: Double = 0.0
    var xlamo: Double = 0.0
    var zmol: Double = 0.0
    var zmos: Double = 0.0
    var atime: Double = 0.0
    var xli: Double = 0.0
    var xni: Double = 0.0

    var a: Double = 0.0
    var alta: Double = 0.0
    var altp: Double = 0.0
    var operationmode: Char = 'i'
    var init: Char = 'y'

    /** Minutes from epoch to perigee passage, derived. Convenience for the UI. */
    val periodMinutes: Double get() = if (no <= 0.0) 0.0 else TWO_PI / no
}

object Sgp4 {

    /**
     * Parses the two lines of a TLE and initialises the propagator.
     *
     * Returns `null` for anything that is not a well-formed pair of lines rather
     * than throwing: the catalogue feed is remote and occasionally truncates.
     * A record that parses but has bad orbital elements still initialises, and
     * [propagate] will report the error.
     */
    fun twoline2satrec(line1: String, line2: String): SatRec? {
        if (line1.length < 61 || line2.length < 63) return null
        val satnum = line1.substring(2, 7)
        val epochyr = line1.substring(18, 20).trim().toIntOrNull() ?: return null
        val epochdays = line1.substring(20, 32).trim().toDoubleOrNull() ?: return null
        val ndot = line1.substring(33, 43).trim().toDoubleOrNull() ?: 0.0
        val nddot = parseExp(line1, 44, 45, 45, 50, 50, 52)
        val bstar = parseExp(line1, 53, 54, 54, 59, 59, 61)

        val inclo = (line2.substring(8, 16).trim().toDoubleOrNull() ?: return null) * DEG2RAD
        val nodeo = (line2.substring(17, 25).trim().toDoubleOrNull() ?: return null) * DEG2RAD
        val ecco = ("." + line2.substring(26, 33).trim()).toDoubleOrNull() ?: return null
        val argpo = (line2.substring(34, 42).trim().toDoubleOrNull() ?: return null) * DEG2RAD
        val mo = (line2.substring(43, 51).trim().toDoubleOrNull() ?: return null) * DEG2RAD
        val no = (line2.substring(52, 63).trim().toDoubleOrNull() ?: return null) / XPDOTP

        // TLE years are two digits; 57 is the epoch of the first satellite.
        val year = if (epochyr < 57) epochyr + 2000 else epochyr + 1900
        val md = days2mdhms(year, epochdays)
        val jdsatepoch = jday(year, md.mon, md.day, md.hr, md.minute, md.sec)

        val rec = SatRec()
        rec.satnum = satnum
        rec.epochyr = epochyr
        rec.epochdays = epochdays
        rec.ndot = ndot
        rec.nddot = nddot
        rec.bstar = bstar
        rec.inclo = inclo
        rec.nodeo = nodeo
        rec.ecco = ecco
        rec.argpo = argpo
        rec.mo = mo
        rec.no = no
        rec.jdsatepoch = jdsatepoch

        sgp4init(rec, jdsatepoch - 2433281.5)
        return rec
    }

    /**
     * Propagates [rec] to [tsince] minutes from its epoch.
     *
     * Returns `null` and sets [SatRec.error] when the orbit has decayed, the
     * elements are out of range, or the propagator diverged. Callers treat null
     * as "no position", never as a zero position.
     */
    fun propagate(rec: SatRec, tsince: Double): EciState? {
        var coseo1 = 0.0
        var sineo1 = 0.0
        var cosip: Double
        var sinip: Double
        var cosisq: Double
        var delm: Double
        var delomg: Double
        var eo1: Double
        var argpm: Double
        var argpp: Double
        var su: Double
        var t3: Double
        var t4: Double
        var tc: Double
        var tem5: Double
        var temp: Double
        var tempa: Double
        var tempe: Double
        var templ: Double
        var inclm: Double
        var mm: Double
        var nm: Double
        var nodem: Double
        var xincp: Double
        var xlm: Double
        var mp: Double
        var nodep: Double

        val temp4 = 1.5e-12
        rec.t = tsince
        rec.error = 0

        val xmdf = rec.mo + rec.mdot * rec.t
        val argpdf = rec.argpo + rec.argpdot * rec.t
        val nodedf = rec.nodeo + rec.nodedot * rec.t
        argpm = argpdf
        mm = xmdf
        val t2 = rec.t * rec.t
        nodem = nodedf + rec.nodecf * t2
        tempa = 1.0 - rec.cc1 * rec.t
        tempe = rec.bstar * rec.cc4 * rec.t
        templ = rec.t2cof * t2
        if (rec.isimp != 1) {
            delomg = rec.omgcof * rec.t
            val delmtemp = 1.0 + rec.eta * cos(xmdf)
            delm = rec.xmcof * (delmtemp * delmtemp * delmtemp - rec.delmo)
            temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            t3 = t2 * rec.t
            t4 = t3 * rec.t
            tempa = tempa - rec.d2 * t2 - rec.d3 * t3 - rec.d4 * t4
            tempe += rec.bstar * rec.cc5 * (sin(mm) - rec.sinmao)
            templ = templ + rec.t3cof * t3 + t4 * (rec.t4cof + rec.t * rec.t5cof)
        }
        nm = rec.no
        var em = rec.ecco
        inclm = rec.inclo
        if (rec.method == 'd') {
            tc = rec.t
            val ds = dspace(
                irez = rec.irez,
                d2201 = rec.d2201, d2211 = rec.d2211, d3210 = rec.d3210, d3222 = rec.d3222,
                d4410 = rec.d4410, d4422 = rec.d4422, d5220 = rec.d5220, d5232 = rec.d5232,
                d5421 = rec.d5421, d5433 = rec.d5433,
                dedt = rec.dedt, del1 = rec.del1, del2 = rec.del2, del3 = rec.del3,
                didt = rec.didt, dmdt = rec.dmdt, dnodt = rec.dnodt, domdt = rec.domdt,
                argpo = rec.argpo, argpdot = rec.argpdot, t = rec.t, tc = tc,
                gsto = rec.gsto, xfact = rec.xfact, xlamo = rec.xlamo, no = rec.no,
                atimeIn = rec.atime, em = em, argpm = argpm, inclm = inclm,
                xliIn = rec.xli, mm = mm, xniIn = rec.xni, nodem = nodem, nmIn = nm
            )
            em = ds.em
            argpm = ds.argpm
            inclm = ds.inclm
            mm = ds.mm
            nodem = ds.nodem
            nm = ds.nm
        }
        if (nm <= 0.0) {
            rec.error = 2
            return null
        }
        var am = (XKE / nm).pow(X2O3) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) {
            rec.error = 1
            return null
        }
        if (em < 1.0e-6) em = 1.0e-6
        mm += rec.no * templ
        xlm = mm + argpm + nodem
        nodem %= TWO_PI
        argpm %= TWO_PI
        xlm %= TWO_PI
        mm = (xlm - argpm - nodem) % TWO_PI

        val sinim = sin(inclm)
        val cosim = cos(inclm)

        var ep = em
        xincp = inclm
        argpp = argpm
        nodep = nodem
        mp = mm
        sinip = sinim
        cosip = cosim

        if (rec.method == 'd') {
            val dp = dpper(
                rec = rec,
                init = false,
                epIn = ep, inclpIn = xincp, nodepIn = nodep, argppIn = argpp, mpIn = mp,
                opsmodeAfspc = rec.operationmode == 'a'
            )
            ep = dp.ep
            nodep = dp.nodep
            argpp = dp.argpp
            mp = dp.mp
            xincp = dp.inclp
            if (xincp < 0.0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0.0 || ep > 1.0) {
                rec.error = 3
                return null
            }
        }

        if (rec.method == 'd') {
            sinip = sin(xincp)
            cosip = cos(xincp)
            rec.aycof = -0.5 * J3OJ2 * sinip
            rec.xlcof = if (abs(cosip + 1.0) > 1.5e-12) {
                -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
            } else {
                -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / temp4
            }
        }

        val axnl = ep * cos(argpp)
        temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * rec.aycof
        val xl = mp + argpp + nodep + temp * rec.xlcof * axnl

        val u = (xl - nodep) % TWO_PI
        eo1 = u
        tem5 = 9999.9
        var ktr = 1
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr += 1
        }

        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) {
            rec.error = 4
            return null
        }
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        if (rec.method == 'd') {
            cosisq = cosip * cosip
            rec.con41 = 3.0 * cosisq - 1.0
            rec.x1mth2 = 1.0 - cosisq
            rec.x7thm1 = 7.0 * cosisq - 1.0
        }

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * rec.con41) +
            0.5 * temp1 * rec.x1mth2 * cos2u
        if (mrt < 1.0) {
            rec.error = 6
            return null
        }
        su -= 0.25 * temp2 * rec.x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * rec.x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (rec.x1mth2 * cos2u + 1.5 * rec.con41) / XKE

        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        return EciState(
            position = Eci(mrt * ux * EARTH_RADIUS, mrt * uy * EARTH_RADIUS, mrt * uz * EARTH_RADIUS),
            velocity = Eci(
                (mvt * ux + rvdot * vx) * VKMPERSEC,
                (mvt * uy + rvdot * vy) * VKMPERSEC,
                (mvt * uz + rvdot * vz) * VKMPERSEC
            )
        )
    }

    /**
     * Full propagation to a geodetic position at [atMillis] (UTC epoch ms).
     * This is what the globe actually calls; [propagate] is the primitive.
     */
    fun positionAt(rec: SatRec, atMillis: Long): Geodetic? {
        val jd = jdayOfMillis(atMillis)
        val tsince = (jd - rec.jdsatepoch) * 1440.0
        val state = propagate(rec, tsince) ?: return null
        return eciToGeodetic(state.position, gstime(jd))
    }

    /** Julian date for a UTC epoch-millisecond timestamp. */
    fun jdayOfMillis(millis: Long): Double {
        val days = millis / 86_400_000.0
        // 1970-01-01T00:00:00Z is JD 2440587.5
        return 2440587.5 + days
    }

    /** Greenwich sidereal time for a UT1 Julian date, radians. */
    fun gstime(jdut1: Double): Double {
        val tut1 = (jdut1 - 2451545.0) / 36525.0
        var temp = -6.2e-6 * tut1 * tut1 * tut1 +
            0.093104 * tut1 * tut1 +
            (876600.0 * 3600 + 8640184.812866) * tut1 +
            67310.54841
        temp = temp * DEG2RAD / 240.0 % TWO_PI
        if (temp < 0.0) temp += TWO_PI
        return temp
    }

    /** Earth-centred inertial to geodetic, WGS-84 ellipsoid. */
    fun eciToGeodetic(eci: Eci, gmst: Double): Geodetic {
        val a = 6378.137
        val b = 6356.7523142
        val r = sqrt(eci.x * eci.x + eci.y * eci.y)
        val f = (a - b) / a
        val e2 = 2 * f - f * f
        var longitude = atan2(eci.y, eci.x) - gmst
        while (longitude < -PI) longitude += TWO_PI
        while (longitude > PI) longitude -= TWO_PI
        var latitude = atan2(eci.z, r)
        var c = 1.0
        var k = 0
        while (k++ < 20) {
            c = 1 / sqrt(1 - e2 * (sin(latitude) * sin(latitude)))
            latitude = atan2(eci.z + a * c * e2 * sin(latitude), r)
        }
        val height = r / cos(latitude) - a * c
        return Geodetic(latitude * RAD2DEG, longitude * RAD2DEG, height)
    }

    // ---------------------------------------------------------------- init

    private fun sgp4init(satrec: SatRec, epoch: Double) {
        val temp4 = 1.5e-12
        var sfour: Double
        var qzms24: Double
        var tsi: Double
        var cc3: Double
        var coef: Double
        var coef1: Double
        var cosio4: Double
        var etasq: Double
        var eeta: Double
        var psisq: Double
        var temp: Double
        var temp1: Double
        var temp2: Double
        var temp3: Double
        var xhdot1: Double
        var xpidot: Double

        satrec.isimp = 0
        satrec.method = 'n'
        satrec.aycof = 0.0
        satrec.con41 = 0.0
        satrec.cc1 = 0.0
        satrec.cc4 = 0.0
        satrec.cc5 = 0.0
        satrec.d2 = 0.0
        satrec.d3 = 0.0
        satrec.d4 = 0.0
        satrec.delmo = 0.0
        satrec.eta = 0.0
        satrec.argpdot = 0.0
        satrec.omgcof = 0.0
        satrec.sinmao = 0.0
        satrec.t = 0.0
        satrec.t2cof = 0.0
        satrec.t3cof = 0.0
        satrec.t4cof = 0.0
        satrec.t5cof = 0.0
        satrec.x1mth2 = 0.0
        satrec.x7thm1 = 0.0
        satrec.mdot = 0.0
        satrec.nodedot = 0.0
        satrec.xlcof = 0.0
        satrec.xmcof = 0.0
        satrec.nodecf = 0.0

        satrec.irez = 0
        satrec.d2201 = 0.0
        satrec.d2211 = 0.0
        satrec.d3210 = 0.0
        satrec.d3222 = 0.0
        satrec.d4410 = 0.0
        satrec.d4422 = 0.0
        satrec.d5220 = 0.0
        satrec.d5232 = 0.0
        satrec.d5421 = 0.0
        satrec.d5433 = 0.0
        satrec.dedt = 0.0
        satrec.del1 = 0.0
        satrec.del2 = 0.0
        satrec.del3 = 0.0
        satrec.didt = 0.0
        satrec.dmdt = 0.0
        satrec.dnodt = 0.0
        satrec.domdt = 0.0
        satrec.e3 = 0.0
        satrec.ee2 = 0.0
        satrec.peo = 0.0
        satrec.pgho = 0.0
        satrec.pho = 0.0
        satrec.pinco = 0.0
        satrec.plo = 0.0
        satrec.se2 = 0.0
        satrec.se3 = 0.0
        satrec.sgh2 = 0.0
        satrec.sgh3 = 0.0
        satrec.sgh4 = 0.0
        satrec.sh2 = 0.0
        satrec.sh3 = 0.0
        satrec.si2 = 0.0
        satrec.si3 = 0.0
        satrec.sl2 = 0.0
        satrec.sl3 = 0.0
        satrec.sl4 = 0.0
        satrec.gsto = 0.0
        satrec.xfact = 0.0
        satrec.xgh2 = 0.0
        satrec.xgh3 = 0.0
        satrec.xgh4 = 0.0
        satrec.xh2 = 0.0
        satrec.xh3 = 0.0
        satrec.xi2 = 0.0
        satrec.xi3 = 0.0
        satrec.xl2 = 0.0
        satrec.xl3 = 0.0
        satrec.xl4 = 0.0
        satrec.xlamo = 0.0
        satrec.zmol = 0.0
        satrec.zmos = 0.0
        satrec.atime = 0.0
        satrec.xli = 0.0
        satrec.xni = 0.0

        satrec.operationmode = 'i'
        val ss = 78.0 / EARTH_RADIUS + 1.0
        val qzms2ttemp = (120.0 - 78.0) / EARTH_RADIUS
        val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp
        satrec.init = 'y'
        satrec.t = 0.0

        val il = initl(satrec.ecco, epoch, satrec.inclo, satrec.no, satrec.operationmode == 'a')
        val ao = il.ao
        val con42 = il.con42
        val cosio = il.cosio
        val cosio2 = il.cosio2
        val eccsq = il.eccsq
        val omeosq = il.omeosq
        val posq = il.posq
        val rp = il.rp
        val rteosq = il.rteosq
        val sinio = il.sinio
        satrec.no = il.no
        satrec.con41 = il.con41
        satrec.gsto = il.gsto
        satrec.a = (satrec.no * TUMIN).pow(-2.0 / 3.0)
        satrec.alta = satrec.a * (1.0 + satrec.ecco) - 1.0
        satrec.altp = satrec.a * (1.0 - satrec.ecco) - 1.0
        satrec.error = 0

        if (omeosq >= 0.0 || satrec.no >= 0.0) {
            satrec.isimp = 0
            if (rp < 220.0 / EARTH_RADIUS + 1.0) satrec.isimp = 1
            sfour = ss
            qzms24 = qzms2t
            val perige = (rp - 1.0) * EARTH_RADIUS
            if (perige < 156.0) {
                sfour = perige - 78.0
                if (perige < 98.0) sfour = 20.0
                val qzms24temp = (120.0 - sfour) / EARTH_RADIUS
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
                sfour = sfour / EARTH_RADIUS + 1.0
            }
            val pinvsq = 1.0 / posq
            tsi = 1.0 / (ao - sfour)
            satrec.eta = ao * satrec.ecco * tsi
            etasq = satrec.eta * satrec.eta
            eeta = satrec.ecco * satrec.eta
            psisq = abs(1.0 - etasq)
            coef = qzms24 * tsi.pow(4.0)
            coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * satrec.no *
                (
                    ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                        0.375 * J2 * tsi / psisq * satrec.con41 * (8.0 + 3.0 * etasq * (8.0 + etasq))
                    )
            satrec.cc1 = satrec.bstar * cc2
            cc3 = 0.0
            if (satrec.ecco > 1.0e-4) {
                cc3 = -2.0 * coef * tsi * J3OJ2 * satrec.no * sinio / satrec.ecco
            }
            satrec.x1mth2 = 1.0 - cosio2
            satrec.cc4 = 2.0 * satrec.no * coef1 * ao * omeosq *
                (
                    satrec.eta * (2.0 + 0.5 * etasq) + satrec.ecco * (0.5 + 2.0 * etasq) -
                        J2 * tsi / (ao * psisq) *
                        (
                            -3.0 * satrec.con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                                0.75 * satrec.x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) *
                                cos(2.0 * satrec.argpo)
                            )
                    )
            satrec.cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            cosio4 = cosio2 * cosio2
            temp1 = 1.5 * J2 * pinvsq * satrec.no
            temp2 = 0.5 * temp1 * J2 * pinvsq
            temp3 = -0.46875 * J4 * pinvsq * pinvsq * satrec.no
            satrec.mdot = satrec.no + 0.5 * temp1 * rteosq * satrec.con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            satrec.argpdot = -0.5 * temp1 * con42 +
                0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            xhdot1 = -temp1 * cosio
            satrec.nodedot = xhdot1 +
                (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            xpidot = satrec.argpdot + satrec.nodedot
            satrec.omgcof = satrec.bstar * cc3 * cos(satrec.argpo)
            satrec.xmcof = 0.0
            if (satrec.ecco > 1.0e-4) {
                satrec.xmcof = -X2O3 * coef * satrec.bstar / eeta
            }
            satrec.nodecf = 3.5 * omeosq * xhdot1 * satrec.cc1
            satrec.t2cof = 1.5 * satrec.cc1
            satrec.xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
                -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
            } else {
                -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / temp4
            }
            satrec.aycof = -0.5 * J3OJ2 * sinio
            val delmotemp = 1.0 + satrec.eta * cos(satrec.mo)
            satrec.delmo = delmotemp * delmotemp * delmotemp
            satrec.sinmao = sin(satrec.mo)
            satrec.x7thm1 = 7.0 * cosio2 - 1.0

            // Deep space branch: periods longer than 225 minutes need the
            // lunar-solar and resonance terms.
            if (2 * PI / satrec.no >= 225.0) {
                satrec.method = 'd'
                satrec.isimp = 1
                val tc = 0.0
                val inclm = satrec.inclo
                val dc = dscom(
                    epoch = epoch, ep = satrec.ecco, argpp = satrec.argpo, tc = tc,
                    inclp = satrec.inclo, nodep = satrec.nodeo, np = satrec.no
                )
                satrec.e3 = dc.e3
                satrec.ee2 = dc.ee2
                satrec.peo = dc.peo
                satrec.pgho = dc.pgho
                satrec.pho = dc.pho
                satrec.pinco = dc.pinco
                satrec.plo = dc.plo
                satrec.se2 = dc.se2
                satrec.se3 = dc.se3
                satrec.sgh2 = dc.sgh2
                satrec.sgh3 = dc.sgh3
                satrec.sgh4 = dc.sgh4
                satrec.sh2 = dc.sh2
                satrec.sh3 = dc.sh3
                satrec.si2 = dc.si2
                satrec.si3 = dc.si3
                satrec.sl2 = dc.sl2
                satrec.sl3 = dc.sl3
                satrec.sl4 = dc.sl4
                satrec.xgh2 = dc.xgh2
                satrec.xgh3 = dc.xgh3
                satrec.xgh4 = dc.xgh4
                satrec.xh2 = dc.xh2
                satrec.xh3 = dc.xh3
                satrec.xi2 = dc.xi2
                satrec.xi3 = dc.xi3
                satrec.xl2 = dc.xl2
                satrec.xl3 = dc.xl3
                satrec.xl4 = dc.xl4
                satrec.zmol = dc.zmol
                satrec.zmos = dc.zmos

                val dp = dpper(
                    rec = satrec, init = true,
                    epIn = satrec.ecco, inclpIn = satrec.inclo, nodepIn = satrec.nodeo,
                    argppIn = satrec.argpo, mpIn = satrec.mo,
                    opsmodeAfspc = satrec.operationmode == 'a'
                )
                satrec.ecco = dp.ep
                satrec.inclo = dp.inclp
                satrec.nodeo = dp.nodep
                satrec.argpo = dp.argpp
                satrec.mo = dp.mp

                val di = dsinit(
                    cosim = dc.cosim, emsqIn = dc.emsq, argpo = satrec.argpo,
                    s1 = dc.s1, s2 = dc.s2, s3 = dc.s3, s4 = dc.s4, s5 = dc.s5,
                    sinim = dc.sinim,
                    ss1 = dc.ss1, ss2 = dc.ss2, ss3 = dc.ss3, ss4 = dc.ss4, ss5 = dc.ss5,
                    sz1 = dc.sz1, sz3 = dc.sz3, sz11 = dc.sz11, sz13 = dc.sz13,
                    sz21 = dc.sz21, sz23 = dc.sz23, sz31 = dc.sz31, sz33 = dc.sz33,
                    t = satrec.t, tc = tc, gsto = satrec.gsto, mo = satrec.mo,
                    mdot = satrec.mdot, no = satrec.no, nodeo = satrec.nodeo,
                    nodedot = satrec.nodedot, xpidot = xpidot,
                    z1 = dc.z1, z3 = dc.z3, z11 = dc.z11, z13 = dc.z13,
                    z21 = dc.z21, z23 = dc.z23, z31 = dc.z31, z33 = dc.z33,
                    ecco = satrec.ecco, eccsq = eccsq, em = dc.em,
                    argpm = 0.0, inclm = inclm, mm = 0.0, nm = dc.nm, nodem = 0.0
                )
                satrec.irez = di.irez
                satrec.atime = di.atime
                satrec.d2201 = di.d2201
                satrec.d2211 = di.d2211
                satrec.d3210 = di.d3210
                satrec.d3222 = di.d3222
                satrec.d4410 = di.d4410
                satrec.d4422 = di.d4422
                satrec.d5220 = di.d5220
                satrec.d5232 = di.d5232
                satrec.d5421 = di.d5421
                satrec.d5433 = di.d5433
                satrec.dedt = di.dedt
                satrec.didt = di.didt
                satrec.dmdt = di.dmdt
                satrec.dnodt = di.dnodt
                satrec.domdt = di.domdt
                satrec.del1 = di.del1
                satrec.del2 = di.del2
                satrec.del3 = di.del3
                satrec.xfact = di.xfact
                satrec.xlamo = di.xlamo
                satrec.xli = di.xli
                satrec.xni = di.xni
            }

            if (satrec.isimp != 1) {
                val cc1sq = satrec.cc1 * satrec.cc1
                satrec.d2 = 4.0 * ao * tsi * cc1sq
                temp = satrec.d2 * tsi * satrec.cc1 / 3.0
                satrec.d3 = (17.0 * ao + sfour) * temp
                satrec.d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * satrec.cc1
                satrec.t3cof = satrec.d2 + 2.0 * cc1sq
                satrec.t4cof = 0.25 * (3.0 * satrec.d3 + satrec.cc1 * (12.0 * satrec.d2 + 10.0 * cc1sq))
                satrec.t5cof = 0.2 * (
                    3.0 * satrec.d4 + 12.0 * satrec.cc1 * satrec.d3 +
                        6.0 * satrec.d2 * satrec.d2 + 15.0 * cc1sq * (2.0 * satrec.d2 + cc1sq)
                    )
            }
        }
        propagate(satrec, 0.0)
        satrec.init = 'n'
    }

    private class InitlResult(
        val no: Double, val ao: Double, val con41: Double, val con42: Double,
        val cosio: Double, val cosio2: Double, val eccsq: Double, val omeosq: Double,
        val posq: Double, val rp: Double, val rteosq: Double, val sinio: Double,
        val gsto: Double
    )

    private fun initl(ecco: Double, epoch: Double, inclo: Double, noIn: Double, afspc: Boolean): InitlResult {
        var no = noIn
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (XKE / no).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var delPrime = d1 / (ak * ak)
        val adel = ak * (1.0 - delPrime * delPrime - delPrime * (1.0 / 3.0 + 134.0 * delPrime * delPrime / 81.0))
        delPrime = d1 / (adel * adel)
        no /= 1.0 + delPrime
        val ao = (XKE / no).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        val con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)
        val gsto: Double = if (afspc) {
            val ts70 = epoch - 7305.0
            val ds70 = floor(ts70 + 1.0e-8)
            val tfrac = ts70 - ds70
            val c1 = 1.72027916940703639e-2
            val thgr70 = 1.7321343856509374
            val fk5r = 5.07551419432269442e-15
            val c1p2p = c1 + TWO_PI
            var g = (thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r) % TWO_PI
            if (g < 0.0) g += TWO_PI
            g
        } else {
            gstime(epoch + 2433281.5)
        }
        return InitlResult(no, ao, con41, con42, cosio, cosio2, eccsq, omeosq, posq, rp, rteosq, sinio, gsto)
    }

    private class DscomResult(
        val sinim: Double, val cosim: Double, val em: Double, val emsq: Double,
        val nm: Double, val e3: Double, val ee2: Double,
        val peo: Double, val pgho: Double, val pho: Double, val pinco: Double, val plo: Double,
        val se2: Double, val se3: Double,
        val sgh2: Double, val sgh3: Double, val sgh4: Double, val sh2: Double, val sh3: Double,
        val si2: Double, val si3: Double, val sl2: Double, val sl3: Double, val sl4: Double,
        val s1: Double, val s2: Double, val s3: Double, val s4: Double, val s5: Double,
        val ss1: Double, val ss2: Double, val ss3: Double, val ss4: Double, val ss5: Double,
        val sz1: Double, val sz3: Double, val sz11: Double, val sz13: Double,
        val sz21: Double, val sz23: Double, val sz31: Double, val sz33: Double,
        val xgh2: Double, val xgh3: Double, val xgh4: Double, val xh2: Double, val xh3: Double,
        val xi2: Double, val xi3: Double, val xl2: Double, val xl3: Double, val xl4: Double,
        val zmol: Double, val zmos: Double,
        val z1: Double, val z3: Double, val z11: Double, val z13: Double,
        val z21: Double, val z23: Double, val z31: Double, val z33: Double
    )

    private fun dscom(
        epoch: Double, ep: Double, argpp: Double, tc: Double,
        inclp: Double, nodep: Double, np: Double
    ): DscomResult {
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458

        val nm = np
        val em = ep
        val snodm = sin(nodep)
        val cnodm = cos(nodep)
        val sinomm = sin(argpp)
        val cosomm = cos(argpp)
        val sinim = sin(inclp)
        val cosim = cos(inclp)
        val emsq = em * em
        val betasq = 1.0 - emsq
        val rtemsq = sqrt(betasq)

        val peo = 0.0
        val pinco = 0.0
        val plo = 0.0
        val pgho = 0.0
        val pho = 0.0
        val day = epoch + 18261.5 + tc / 1440.0
        val xnodce = (4.5236020 - 9.2422029e-4 * day) % TWO_PI
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx += gam - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = cnodm
        var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm

        var ss1 = 0.0
        var ss2 = 0.0
        var ss3 = 0.0
        var ss4 = 0.0
        var ss5 = 0.0
        var ss6 = 0.0
        var ss7 = 0.0
        var sz1 = 0.0
        var sz2 = 0.0
        var sz3 = 0.0
        var sz12 = 0.0
        var sz22 = 0.0
        var sz32 = 0.0
        var sz11 = 0.0
        var sz13 = 0.0
        var sz21 = 0.0
        var sz23 = 0.0
        var sz31 = 0.0
        var sz33 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var s5 = 0.0
        var s6 = 0.0
        var s7 = 0.0
        var z1 = 0.0
        var z2 = 0.0
        var z3 = 0.0
        var z11 = 0.0
        var z12 = 0.0
        var z13 = 0.0
        var z21 = 0.0
        var z22 = 0.0
        var z23 = 0.0
        var z31 = 0.0
        var z32 = 0.0
        var z33 = 0.0

        var lsflg = 0
        while (lsflg < 2) {
            lsflg += 1
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10
            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm
            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) +
                emsq * (-24.0 * (x2 * x7 + x1 * x8) + -6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) +
                emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3
            if (lsflg == 1) {
                ss1 = s1
                ss2 = s2
                ss3 = s3
                ss4 = s4
                ss5 = s5
                ss6 = s6
                ss7 = s7
                sz1 = z1
                sz2 = z2
                sz3 = z3
                sz11 = z11
                sz12 = z12
                sz13 = z13
                sz21 = z21
                sz22 = z22
                sz23 = z23
                sz31 = z31
                sz32 = z32
                sz33 = z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }

        val zmol = (4.7199672 + (0.22997150 * day - gam)) % TWO_PI
        val zmos = (6.2565837 + 0.017201977 * day) % TWO_PI

        val se2 = 2.0 * ss1 * ss6
        val se3 = 2.0 * ss1 * ss7
        val si2 = 2.0 * ss2 * sz12
        val si3 = 2.0 * ss2 * (sz13 - sz11)
        val sl2 = -2.0 * ss3 * sz2
        val sl3 = -2.0 * ss3 * (sz3 - sz1)
        val sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes
        val sgh2 = 2.0 * ss4 * sz32
        val sgh3 = 2.0 * ss4 * (sz33 - sz31)
        val sgh4 = -18.0 * ss4 * zes
        val sh2 = -2.0 * ss2 * sz22
        val sh3 = -2.0 * ss2 * (sz23 - sz21)

        val ee2 = 2.0 * s1 * s6
        val e3 = 2.0 * s1 * s7
        val xi2 = 2.0 * s2 * z12
        val xi3 = 2.0 * s2 * (z13 - z11)
        val xl2 = -2.0 * s3 * z2
        val xl3 = -2.0 * s3 * (z3 - z1)
        val xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel
        val xgh2 = 2.0 * s4 * z32
        val xgh3 = 2.0 * s4 * (z33 - z31)
        val xgh4 = -18.0 * s4 * zel
        val xh2 = -2.0 * s2 * z22
        val xh3 = -2.0 * s2 * (z23 - z21)

        return DscomResult(
            sinim = sinim, cosim = cosim, em = em, emsq = emsq, nm = nm,
            e3 = e3, ee2 = ee2,
            peo = peo, pgho = pgho, pho = pho, pinco = pinco, plo = plo,
            se2 = se2, se3 = se3, sgh2 = sgh2, sgh3 = sgh3, sgh4 = sgh4,
            sh2 = sh2, sh3 = sh3, si2 = si2, si3 = si3, sl2 = sl2, sl3 = sl3, sl4 = sl4,
            s1 = s1, s2 = s2, s3 = s3, s4 = s4, s5 = s5,
            ss1 = ss1, ss2 = ss2, ss3 = ss3, ss4 = ss4, ss5 = ss5,
            sz1 = sz1, sz3 = sz3, sz11 = sz11, sz13 = sz13,
            sz21 = sz21, sz23 = sz23, sz31 = sz31, sz33 = sz33,
            xgh2 = xgh2, xgh3 = xgh3, xgh4 = xgh4, xh2 = xh2, xh3 = xh3,
            xi2 = xi2, xi3 = xi3, xl2 = xl2, xl3 = xl3, xl4 = xl4,
            zmol = zmol, zmos = zmos,
            z1 = z1, z3 = z3, z11 = z11, z13 = z13,
            z21 = z21, z23 = z23, z31 = z31, z33 = z33
        )
    }

    private class DsinitResult(
        val em: Double, val argpm: Double, val inclm: Double, val mm: Double,
        val nm: Double, val nodem: Double, val irez: Int, val atime: Double,
        val d2201: Double, val d2211: Double, val d3210: Double, val d3222: Double,
        val d4410: Double, val d4422: Double, val d5220: Double, val d5232: Double,
        val d5421: Double, val d5433: Double, val dedt: Double, val didt: Double,
        val dmdt: Double, val dnodt: Double, val domdt: Double,
        val del1: Double, val del2: Double, val del3: Double,
        val xfact: Double, val xlamo: Double, val xli: Double, val xni: Double
    )

    @Suppress("LongParameterList")
    private fun dsinit(
        cosim: Double, emsqIn: Double, argpo: Double,
        s1: Double, s2: Double, s3: Double, s4: Double, s5: Double,
        sinim: Double,
        ss1: Double, ss2: Double, ss3: Double, ss4: Double, ss5: Double,
        sz1: Double, sz3: Double, sz11: Double, sz13: Double,
        sz21: Double, sz23: Double, sz31: Double, sz33: Double,
        t: Double, tc: Double, gsto: Double, mo: Double, mdot: Double, no: Double,
        nodeo: Double, nodedot: Double, xpidot: Double,
        z1: Double, z3: Double, z11: Double, z13: Double,
        z21: Double, z23: Double, z31: Double, z33: Double,
        ecco: Double, eccsq: Double, em: Double,
        argpm: Double, inclm: Double, mm: Double, nm: Double, nodem: Double
    ): DsinitResult {
        var em = em
        var emsq = emsqIn
        var argpm = argpm
        var inclm = inclm
        var mm = mm
        var nm = nm
        var nodem = nodem

        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val znl = 1.5835218e-4
        val zns = 1.19459e-5

        var irez = 0
        if (nm < 0.0052359877 && nm > 0.0034906585) irez = 1
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2

        val ses = ss1 * zns * ss5
        val sis = ss2 * zns * (sz11 + sz13)
        val sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq)
        val sghs = ss4 * zns * (sz31 + sz33 - 6.0)
        var shs = -zns * ss2 * (sz21 + sz23)
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs

        var dedt = ses + s1 * znl * s5
        var didt = sis + s2 * znl * (z11 + z13)
        var dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * znl * (z31 + z33 - 6.0)
        var shll = -znl * s2 * (z21 + z23)
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0
        var domdt = sgs + sghl
        var dnodt = shs
        if (sinim != 0.0) {
            domdt -= cosim / sinim * shll
            dnodt += shll / sinim
        }

        val dndt = 0.0
        val theta = (gsto + tc * rptim) % TWO_PI
        em += dedt * t
        inclm += didt * t
        argpm += domdt * t
        nodem += dnodt * t
        mm += dmdt * t

        var atime = 0.0
        var d2201 = 0.0
        var d2211 = 0.0
        var d3210 = 0.0
        var d3222 = 0.0
        var d4410 = 0.0
        var d4422 = 0.0
        var d5220 = 0.0
        var d5232 = 0.0
        var d5421 = 0.0
        var d5433 = 0.0
        var del1 = 0.0
        var del2 = 0.0
        var del3 = 0.0
        var xfact = 0.0
        var xlamo = 0.0
        var xli = 0.0
        var xni = 0.0

        if (irez != 0) {
            val aonv = (nm / XKE).pow(X2O3)

            if (irez == 2) {
                val cosisq = cosim * cosim
                val emo = em
                em = ecco
                val emsqo = emsq
                emsq = eccsq
                val eoc = em * emsq
                var g201 = -0.306 - (em - 0.64) * 0.440
                var g211: Double
                var g310: Double
                var g322: Double
                var g410: Double
                var g422: Double
                var g520: Double
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                    g520 = if (em > 0.715) {
                        -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    } else {
                        1464.74 - 4664.75 * em + 3763.64 * emsq
                    }
                }
                var g533: Double
                var g521: Double
                var g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim *
                    (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                        0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
                val f523 = sinim *
                    (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                        6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
                val f542 = 29.53125 * sinim *
                    (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim *
                    (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - (theta + theta)) % TWO_PI
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - no
                em = emo
                emsq = emsqo
            }

            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 *= 1.875 * f330 * f330
                del1 = 3.0 * nm * nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta) % TWO_PI
                xfact = mdot + xpidot + dmdt + domdt + dnodt - (no + rptim)
            }

            xli = xlamo
            xni = no
            atime = 0.0
            nm = no + dndt
        }

        return DsinitResult(
            em, argpm, inclm, mm, nm, nodem, irez, atime,
            d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433,
            dedt, didt, dmdt, dnodt, domdt, del1, del2, del3, xfact, xlamo, xli, xni
        )
    }

    private class DspaceResult(
        val em: Double, val argpm: Double, val inclm: Double,
        val mm: Double, val nodem: Double, val nm: Double
    )

    @Suppress("LongParameterList")
    private fun dspace(
        irez: Int,
        d2201: Double, d2211: Double, d3210: Double, d3222: Double,
        d4410: Double, d4422: Double, d5220: Double, d5232: Double,
        d5421: Double, d5433: Double,
        dedt: Double, del1: Double, del2: Double, del3: Double,
        didt: Double, dmdt: Double, dnodt: Double, domdt: Double,
        argpo: Double, argpdot: Double, t: Double, tc: Double,
        gsto: Double, xfact: Double, xlamo: Double, no: Double,
        atimeIn: Double, em: Double, argpm: Double, inclm: Double,
        xliIn: Double, mm: Double, xniIn: Double, nodem: Double, nmIn: Double
    ): DspaceResult {
        var atime = atimeIn
        var em = em
        var argpm = argpm
        var inclm = inclm
        var xli = xliIn
        var mm = mm
        var xni = xniIn
        var nodem = nodem
        var nm = nmIn

        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0

        var dndt = 0.0
        var ft = 0.0
        var xndt = 0.0
        var xnddt = 0.0
        var xldot = 0.0

        val theta = (gsto + tc * rptim) % TWO_PI
        em += dedt * t
        inclm += didt * t
        argpm += domdt * t
        nodem += dnodt * t
        mm += dmdt * t

        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
                atime = 0.0
                xni = no
                xli = xlamo
            }
            val delt = if (t > 0.0) stepp else stepn
            var iretn = 381
            while (iretn == 381) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) +
                        del2 * sin(2.0 * (xli - fasx4)) +
                        del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - fasx2) +
                        2.0 * del2 * cos(2.0 * (xli - fasx4)) +
                        3.0 * del3 * cos(3.0 * (xli - fasx6))
                    xnddt *= xldot
                } else {
                    val xomi = argpo + argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                        d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                        d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                        d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                        d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                        d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                        2.0 * (
                            d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                                d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54)
                            )
                    xnddt *= xldot
                }
                if (abs(t - atime) >= stepp) {
                    iretn = 381
                } else {
                    ft = t - atime
                    iretn = 0
                }
                if (iretn == 381) {
                    xli += xldot * delt + xndt * step2
                    xni += xndt * delt + xnddt * step2
                    atime += delt
                }
            }
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            if (irez != 1) {
                mm = xl - 2.0 * nodem + 2.0 * theta
                dndt = nm - no
            } else {
                mm = xl - nodem - argpm + theta
                dndt = nm - no
            }
            nm = no + dndt
        }
        return DspaceResult(em, argpm, inclm, mm, nodem, nm)
    }

    private class DpperResult(
        val ep: Double, val inclp: Double, val nodep: Double,
        val argpp: Double, val mp: Double
    )

    @Suppress("LongParameterList")
    private fun dpper(
        rec: SatRec, init: Boolean,
        epIn: Double, inclpIn: Double, nodepIn: Double, argppIn: Double, mpIn: Double,
        opsmodeAfspc: Boolean
    ): DpperResult {
        var ep = epIn
        var inclp = inclpIn
        var nodep = nodepIn
        var argpp = argppIn
        var mp = mpIn

        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490

        var zm = rec.zmos + zns * rec.t
        if (init) zm = rec.zmos
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = rec.se2 * f2 + rec.se3 * f3
        val sis = rec.si2 * f2 + rec.si3 * f3
        val sls = rec.sl2 * f2 + rec.sl3 * f3 + rec.sl4 * sinzf
        val sghs = rec.sgh2 * f2 + rec.sgh3 * f3 + rec.sgh4 * sinzf
        val shs = rec.sh2 * f2 + rec.sh3 * f3

        zm = rec.zmol + znl * rec.t
        if (init) zm = rec.zmol
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = rec.ee2 * f2 + rec.e3 * f3
        val sil = rec.xi2 * f2 + rec.xi3 * f3
        val sll = rec.xl2 * f2 + rec.xl3 * f3 + rec.xl4 * sinzf
        val sghl = rec.xgh2 * f2 + rec.xgh3 * f3 + rec.xgh4 * sinzf
        val shll = rec.xh2 * f2 + rec.xh3 * f3

        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        if (!init) {
            pe -= rec.peo
            pinc -= rec.pinco
            pl -= rec.plo
            pgh -= rec.pgho
            ph -= rec.pho
            inclp += pinc
            ep += pe
            val sinip = sin(inclp)
            val cosip = cos(inclp)

            if (inclp >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                argpp += pgh
                nodep += ph
                mp += pl
            } else {
                // Lyddane modification for near-equatorial orbits.
                val sinop = sin(nodep)
                val cosop = cos(nodep)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                nodep %= TWO_PI
                if (nodep < 0.0 && opsmodeAfspc) nodep += TWO_PI
                var xls = mp + argpp + cosip * nodep
                val dls = pl + pgh - pinc * nodep * sinip
                xls += dls
                val xnoh = nodep
                nodep = atan2(alfdp, betdp)
                if (nodep < 0.0 && opsmodeAfspc) nodep += TWO_PI
                if (abs(xnoh - nodep) > PI) {
                    if (nodep < xnoh) nodep += TWO_PI else nodep -= TWO_PI
                }
                mp += pl
                argpp = xls - mp - cosip * nodep
            }
        }
        return DpperResult(ep, inclp, nodep, argpp, mp)
    }

    // ------------------------------------------------------------ helpers

    /**
     * Reads a TLE implied-decimal exponent field, e.g. ` 12345-4` → 1.2345e-5.
     * The sign of the mantissa is a single character in the field, which is why
     * this cannot go through a plain `toDouble`.
     */
    private fun parseExp(
        line: String,
        signAt: Int, signEnd: Int,
        mantAt: Int, mantEnd: Int,
        expAt: Int, expEnd: Int
    ): Double {
        if (line.length < maxOf(mantEnd, expEnd)) return 0.0
        val sign = line.substring(signAt, signEnd).trim()
        val mant = line.substring(mantAt, mantEnd).trim().replace(" ", "")
        val exp = line.substring(expAt, expEnd).trim()
        if (mant.isEmpty()) return 0.0
        val expVal = exp.toIntOrNull() ?: 0
        val mantVal = ("0.$mant").toDoubleOrNull() ?: return 0.0
        val signed = if (sign == "-") -mantVal else mantVal
        return signed * 10.0.pow(expVal)
    }

    private class Mdhms(val mon: Int, val day: Int, val hr: Int, val minute: Int, val sec: Double)

    private fun days2mdhms(year: Int, days: Double): Mdhms {
        val lmonth = intArrayOf(
            31, if (year % 4 == 0) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
        )
        val dayofyr = floor(days).toInt()
        var i = 1
        var inttemp = 0
        while (dayofyr > inttemp + lmonth[i - 1] && i < 12) {
            inttemp += lmonth[i - 1]
            i += 1
        }
        val mon = i
        val day = dayofyr - inttemp
        var temp = (days - dayofyr) * 24.0
        val hr = floor(temp).toInt()
        temp = (temp - hr) * 60.0
        val minute = floor(temp).toInt()
        val sec = (temp - minute) * 60.0
        return Mdhms(mon, day, hr, minute, sec)
    }

    private fun jday(year: Int, mon: Int, day: Int, hr: Int, minute: Int, sec: Double): Double =
        367.0 * year -
            floor(7 * (year + floor((mon + 9) / 12.0)) * 0.25) +
            floor(275 * mon / 9.0) +
            day +
            1721013.5 +
            ((sec / 60.0 + minute) / 60.0 + hr) / 24.0
}
