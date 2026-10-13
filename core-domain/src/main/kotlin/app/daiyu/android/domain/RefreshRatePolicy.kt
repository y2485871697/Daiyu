package app.daiyu.android.domain

fun highestSupportedRefreshRate(refreshRates: Iterable<Float>): Float {
    var highest = 0f
    refreshRates.forEach { refreshRate ->
        if (refreshRate.isFinite() && refreshRate > highest) {
            highest = refreshRate
        }
    }
    return highest
}
