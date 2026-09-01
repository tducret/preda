package com.example.vrviewer.vr

import org.json.JSONObject

/**
 * Lens and screen parameters for a Cardboard-style viewer, loadable from a JSON profile.
 */
data class CardboardProfile(
    val vendor: String,
    val model: String,
    val screenToLensDistance: Float,
    val interLensDistance: Float,
    val leftEyeFieldOfViewAngles: FloatArray,
    val distortionCoefficients: FloatArray
) {

    /**
     * Compute a viewport scale that inverts the barrel distortion at the corner
     * of the eye viewport so the warped image stays within the texture bounds.
     *
     * This is a first-order approximation; fine-tuning can be done by adjusting
     * the coefficients or adding a runtime scale multiplier.
     */
    fun distortionScale(): Float {
        require(distortionCoefficients.size >= 2) { "At least two distortion coefficients are required" }
        val r2 = 0.5f // squared radius from lens center to viewport corner in UV space
        val r4 = r2 * r2
        val factor = 1.0f +
            distortionCoefficients[0] * r2 +
            distortionCoefficients[1] * r4
        return 1.0f / factor
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CardboardProfile

        if (vendor != other.vendor) return false
        if (model != other.model) return false
        if (screenToLensDistance != other.screenToLensDistance) return false
        if (interLensDistance != other.interLensDistance) return false
        if (!leftEyeFieldOfViewAngles.contentEquals(other.leftEyeFieldOfViewAngles)) return false
        if (!distortionCoefficients.contentEquals(other.distortionCoefficients)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = vendor.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + screenToLensDistance.hashCode()
        result = 31 * result + interLensDistance.hashCode()
        result = 31 * result + leftEyeFieldOfViewAngles.contentHashCode()
        result = 31 * result + distortionCoefficients.contentHashCode()
        return result
    }

    companion object {

        /**
         * Google Cardboard v2 default profile.
         *
         * Values are the official viewer parameters:
         * - screen-to-lens distance: 39 mm
         * - inter-lens distance: 64 mm
         * - left-eye field of view: 50 degrees in each direction
         * - barrel distortion coefficients: k1 = 0.34, k2 = 0.55
         */
        val DEFAULT_V2 = CardboardProfile(
            vendor = "Google",
            model = "Cardboard v2",
            screenToLensDistance = 0.039f,
            interLensDistance = 0.064f,
            leftEyeFieldOfViewAngles = floatArrayOf(50.0f, 50.0f, 50.0f, 50.0f),
            distortionCoefficients = floatArrayOf(0.34f, 0.55f)
        )

        /**
         * Parse a JSON profile string into a [CardboardProfile].
         *
         * Expected format matches the Cardboard viewer profile QR payload:
         * ```json
         * {
         *   "vendor": "Google",
         *   "model": "Cardboard v2",
         *   "screen_to_lens_distance": 0.039,
         *   "inter_lens_distance": 0.064,
         *   "left_eye_field_of_view_angles": [50, 50, 50, 50],
         *   "distortion_coefficients": [0.34, 0.55]
         * }
         * ```
         */
        fun fromJson(json: String): CardboardProfile {
            val root = JSONObject(json)
            return CardboardProfile(
                vendor = root.optString("vendor", ""),
                model = root.optString("model", ""),
                screenToLensDistance = root.getDouble("screen_to_lens_distance").toFloat(),
                interLensDistance = root.getDouble("inter_lens_distance").toFloat(),
                leftEyeFieldOfViewAngles = root.getFloatArray("left_eye_field_of_view_angles"),
                distortionCoefficients = root.getFloatArray("distortion_coefficients")
            )
        }

        private fun JSONObject.getFloatArray(name: String): FloatArray {
            val array = getJSONArray(name)
            return FloatArray(array.length()) { index ->
                array.getDouble(index).toFloat()
            }
        }
    }
}
