package io.github.anastaci1a.localica.ui

/** Shared water-cap optics for controls and connected selection silhouettes. */
internal const val WaterCapOptics = """
    float2 capDisplacement(float3 normal, float height) {
        float3 ray = refract(float3(0, 0, -1), normal, 0.75);
        return ray.xy * height / max(-ray.z, 0.15);
    }
    float capFresnel(float3 normal) {
        return 0.02 + 0.98 * pow(1.0 - normal.z, 5.0);
    }
"""
