package ru.kolco24.kolco24.ui.common

/*
 * Pure (Android-free, JVM-tested by `KeyedValueTest`) helper for key-tagged flow emissions.
 */

/**
 * The value of a key-tagged emission, or `null` while the latest emission belongs to another key.
 * `collectAsState` keeps its value across a `remember(key)` flow swap, so an untagged value would
 * leak the previous race's / team's data through the gate for the frames before the new flow emits.
 */
fun <K, V> valueForKey(tagged: Pair<K, V>?, key: K?): V? =
    if (tagged != null && key != null && tagged.first == key) tagged.second else null
