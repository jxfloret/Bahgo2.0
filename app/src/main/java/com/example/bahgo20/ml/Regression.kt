package com.example.bahgo20.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Linear and Lasso regression, written in plain Kotlin (no Android code) so they can be unit tested.
 *
 * Both models work on standardized features (each column scaled to mean 0, spread 1).
 * That keeps the Lasso penalty fair across features measured in different units
 * (cm vs. rain intensity), and makes the coefficients comparable.
 */

/** Scales each feature column to mean 0 and standard deviation 1, using the training data. */
class Standardizer(val means: DoubleArray, val stds: DoubleArray) {

    fun transform(x: DoubleArray): DoubleArray =
        DoubleArray(x.size) { (x[it] - means[it]) / stds[it] }

    companion object {
        fun fit(rows: List<DoubleArray>): Standardizer {
            require(rows.isNotEmpty()) { "No training rows" }
            val p = rows[0].size
            val n = rows.size
            val means = DoubleArray(p)
            val stds = DoubleArray(p)
            for (r in rows) for (j in 0 until p) means[j] += r[j]
            for (j in 0 until p) means[j] /= n
            for (r in rows) for (j in 0 until p) {
                val d = r[j] - means[j]
                stds[j] += d * d
            }
            for (j in 0 until p) {
                stds[j] = sqrt(stds[j] / n)
                if (stds[j] < 1e-12) stds[j] = 1.0   // constant column: leave it unscaled
            }
            return Standardizer(means, stds)
        }
    }
}

/** A fitted model: prediction = intercept + sum(coef[j] * standardized x[j]). */
class RegressionModel(
    val name: String,
    val scaler: Standardizer,
    val intercept: Double,
    val coef: DoubleArray
) {
    fun predict(x: DoubleArray): Double {
        val z = scaler.transform(x)
        var y = intercept
        for (j in coef.indices) y += coef[j] * z[j]
        return y
    }

    /** Indices of the features the model actually uses (Lasso sets the rest to exactly 0). */
    fun usedFeatures(): List<Int> = coef.indices.filter { coef[it] != 0.0 }
}

/**
 * Shared setup for both models: standardize X, center y, and build
 *   G = Z^T Z / n   (feature-by-feature correlations)
 *   c = Z^T y / n   (feature-to-target correlations)
 * Both regressions only need G and c, so training cost doesn't grow with more rows after this.
 */
private class Prepared(val scaler: Standardizer, val yMean: Double, val g: Array<DoubleArray>, val c: DoubleArray)

private fun prepare(xs: List<DoubleArray>, ys: DoubleArray): Prepared {
    require(xs.size == ys.size && xs.isNotEmpty()) { "X and y must be the same non-zero length" }
    val scaler = Standardizer.fit(xs)
    val n = xs.size
    val p = xs[0].size
    val yMean = ys.average()
    val g = Array(p) { DoubleArray(p) }
    val c = DoubleArray(p)
    for (i in 0 until n) {
        val z = scaler.transform(xs[i])
        val yc = ys[i] - yMean
        for (j in 0 until p) {
            c[j] += z[j] * yc
            for (k in j until p) g[j][k] += z[j] * z[k]
        }
    }
    for (j in 0 until p) {
        c[j] /= n
        for (k in j until p) {
            g[j][k] /= n
            g[k][j] = g[j][k]
        }
    }
    return Prepared(scaler, yMean, g, c)
}

object LinearRegression {
    /**
     * Ordinary least squares: solves G * b = c.
     * A tiny ridge term keeps it solvable when two features move together exactly.
     */
    fun fit(xs: List<DoubleArray>, ys: DoubleArray, ridge: Double = 1e-9): RegressionModel {
        val prep = prepare(xs, ys)
        val p = prep.c.size
        val a = Array(p) { j -> DoubleArray(p) { k -> prep.g[j][k] + if (j == k) ridge else 0.0 } }
        val b = solve(a, prep.c.copyOf())
        return RegressionModel("Linear", prep.scaler, prep.yMean, b)
    }

    /** Gaussian elimination with partial pivoting. Overwrites [a] and [b]. */
    internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (pivot != col) {
                val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
            }
            val d = a[col][col]
            if (abs(d) < 1e-15) continue   // singular direction: leave that coefficient at 0
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                if (f == 0.0) continue
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = if (abs(a[r][r]) < 1e-15) 0.0 else s / a[r][r]
        }
        return x
    }
}

object LassoRegression {
    /**
     * Lasso: minimizes  (1/2n) * sum((y - prediction)^2)  +  alpha * sum(|coef|)
     * by coordinate descent. The |coef| penalty pushes weak features to exactly 0,
     * so Lasso also picks which inputs matter.
     */
    fun fit(
        xs: List<DoubleArray>,
        ys: DoubleArray,
        alpha: Double,
        maxSweeps: Int = 10_000,
        tolerance: Double = 1e-10
    ): RegressionModel {
        require(alpha >= 0) { "alpha must be >= 0" }
        val prep = prepare(xs, ys)
        val g = prep.g
        val c = prep.c
        val p = c.size
        val b = DoubleArray(p)

        for (sweep in 0 until maxSweeps) {
            var biggestChange = 0.0
            for (j in 0 until p) {
                if (g[j][j] <= 0.0) continue
                // Correlation of feature j with what the other features haven't explained yet
                var rho = c[j]
                for (k in 0 until p) if (k != j) rho -= g[j][k] * b[k]
                val newB = softThreshold(rho, alpha) / g[j][j]
                biggestChange = max(biggestChange, abs(newB - b[j]))
                b[j] = newB
            }
            if (biggestChange < tolerance) break
        }
        return RegressionModel("Lasso", prep.scaler, prep.yMean, b)
    }

    internal fun softThreshold(x: Double, t: Double): Double = when {
        x > t -> x - t
        x < -t -> x + t
        else -> 0.0
    }
}

/** Average size of the error, and root-mean-square error (punishes big misses more). */
data class ErrorStats(val mae: Double, val rmse: Double, val count: Int)

fun errorStats(predicted: DoubleArray, actual: DoubleArray): ErrorStats {
    require(predicted.size == actual.size)
    if (predicted.isEmpty()) return ErrorStats(0.0, 0.0, 0)
    var sa = 0.0
    var ss = 0.0
    for (i in predicted.indices) {
        val e = predicted[i] - actual[i]
        sa += abs(e)
        ss += e * e
    }
    return ErrorStats(sa / predicted.size, sqrt(ss / predicted.size), predicted.size)
}
