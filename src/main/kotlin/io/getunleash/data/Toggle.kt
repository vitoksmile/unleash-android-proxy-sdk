package io.getunleash.data

/**
 * An already evaluated toggle.
 * For creating toggles see docs - [Feature toggles](https://docs.getunleash.io/docs/user_guide/create_feature_toggle)
 * @property name Name of the toggle
 * @property enabled Did this toggle get evaluated to true
 * @property variant used by [io.getunleash.UnleashClientSpec.getVariant] to get the variant data
 */
data class Toggle(val name: String, val enabled: Boolean, val variant: Variant = Variant(name = "disabled"))
