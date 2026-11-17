package com.tvassist.ui.maps

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * [vectorZoom] is what keeps a saved map-card zoom meaning the same streets after the move from
 * raster tiles to MapLibre. It has to agree with how the raster screens drew: a 1024 px composite
 * stretched over the screen's longer side.
 */
class VectorZoomTest {

    /** Screen pixels one raster tile at zoom [z] covered, for a world of 256·2^z px scaled by maxDim/1024. */
    private fun rasterPxPerWorld(z: Int, maxDimPx: Int) = 256.0 * 2.0.pow(z) * maxDimPx / 1024.0

    /** Screen pixels the same world spans in MapLibre at [zoom]: 512 dp per tile. */
    private fun vectorPxPerWorld(zoom: Double, density: Float) = 512.0 * density * 2.0.pow(zoom)

    @Test
    fun framesTheSameGroundAsTheRasterScreens() {
        // Both BRAVIAs: 1920x1080 logical at density 2.0 (the VH21 is a 4K panel run at 1080p).
        for (z in listOf(3, 14, 16, 20)) {
            val zoom = vectorZoom(z, maxDimPx = 1920, density = 2f)
            assertEquals(rasterPxPerWorld(z, 1920), vectorPxPerWorld(zoom, 2f), 1e-6 * rasterPxPerWorld(z, 1920))
        }
    }

    @Test
    fun onABraviaItIsAboutOneLevelBelowTheRasterZoom() {
        assertEquals(16 - 1.0931, vectorZoom(16, maxDimPx = 1920, density = 2f), 1e-3)
    }

    @Test
    fun aDenserScreenZoomsOutToShowTheSameArea() {
        assertEquals(1.0, vectorZoom(14, 1920, 1f) - vectorZoom(14, 1920, 2f), 1e-9)
    }
}
