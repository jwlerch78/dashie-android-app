package com.dashieapp.Dashie.halite.settings.schema

/**
 * Evaluates [Condition] trees against the current settings state.
 * Used to determine visibility and enabled state of schema items at render time.
 */
class ConditionEvaluator(private val valueProvider: SettingsValueProvider) {

    /**
     * Evaluate a condition. Returns true if condition is null (unconditionally visible/enabled).
     */
    fun evaluate(condition: Condition?): Boolean {
        if (condition == null) return true
        return when (condition) {
            is Condition.IsTrue -> valueProvider.getBoolean(condition.key)
            is Condition.IsFalse -> !valueProvider.getBoolean(condition.key)
            is Condition.Equals -> valueProvider.getString(condition.key) == condition.value.toString()
            is Condition.NotEquals -> valueProvider.getString(condition.key) != condition.value.toString()
            is Condition.And -> condition.conditions.all { evaluate(it) }
            is Condition.Or -> condition.conditions.any { evaluate(it) }
            is Condition.Never -> false
        }
    }
}
