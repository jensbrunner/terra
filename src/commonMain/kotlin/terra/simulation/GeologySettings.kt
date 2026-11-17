package terra.simulation

/** Live controls, independent of world generation and calendar playback speed. */
data class GeologySettings(val speedMultiplier: Int = 10_000, val updateYears: Int = 100) {
    init {
        require(speedMultiplier in 1_000..10_000)
        require(updateYears in 10..100)
    }
}

data class GeologyStatus(val settings: GeologySettings, val effectiveYears: Double)
