package ru.kolco24.kolco24.data

/**
 * Which server a sync repo should fetch from. [Cloud] is the default for every existing call
 * site; [Local] targets the LAN race-day server (`AppContainer.localApiClient`). See the
 * local-mode-switch plan for the cloud-persist guard this drives.
 */
enum class SyncSource { Cloud, Local }
