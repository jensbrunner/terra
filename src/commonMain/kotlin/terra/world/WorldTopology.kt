package terra.world

/** Both map axes are periodic. Shortest deltas also work with unwrapped coordinates. */
internal fun wrapIndex(value: Int, size: Int) = if (value >= 0 && value < size) value else ((value % size) + size) % size
internal fun wrapPosition(value: Double, period: Double) = ((value % period) + period) % period
internal fun periodicDelta(value: Double, period: Double) = wrapPosition(value + period * 0.5, period) - period * 0.5
internal fun worldIndex(x: Int, y: Int, width: Int, height: Int) = wrapIndex(y, height) * width + wrapIndex(x, width)
