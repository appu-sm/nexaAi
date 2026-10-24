package com.nexaai.assistant.execution

import com.nexaai.assistant.data.AppAllowlistRepository
import com.nexaai.assistant.data.ConfigurationRepository
import kotlinx.coroutines.flow.first

class CommandRuntime(
    repository: ConfigurationRepository,
    val allowlist: AppAllowlistRepository,
    val appDevice: AppDevice,
    volume: VolumeController,
    media: MediaController,
) {
    val dispatcher = CommandDispatcher(
        configuration = { ExecutionConfiguration(repository.snapshots.first(), allowlist.apps()) },
        volume = volume,
        apps = AllowlistedAppLauncher(appDevice, allowlist::apps),
        media = media,
        radio = UnsupportedRadioController(),
    )
}