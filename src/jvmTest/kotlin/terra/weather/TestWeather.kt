package terra.weather

import terra.atmosphere.DryAtmosphereConfig
import terra.world.World

internal fun testWeather(world: World): WeatherModel = DynamicalWeather(world,
    DryAtmosphereConfig(width = 8, height = 6))
