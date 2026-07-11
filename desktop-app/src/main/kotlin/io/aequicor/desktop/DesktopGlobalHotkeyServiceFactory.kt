package io.aequicor.desktop

import io.aequicor.hotkey.GlobalHotkeyBinding
import io.aequicor.hotkey.GlobalHotkeyService
import io.aequicor.hotkey.GlobalHotkeyServiceFactory
import io.aequicor.hotkey.linux.x11.LinuxX11GlobalHotkeyServiceFactory
import io.aequicor.hotkey.macos.carbon.MacCarbonGlobalHotkeyServiceFactory
import io.aequicor.hotkey.windows.jna.WindowsGlobalHotkeyServiceFactory

internal class DesktopGlobalHotkeyServiceFactory(
    private val platformFactories: List<GlobalHotkeyServiceFactory> = listOf(
        WindowsGlobalHotkeyServiceFactory(),
        LinuxX11GlobalHotkeyServiceFactory(),
        MacCarbonGlobalHotkeyServiceFactory(),
    ),
) : GlobalHotkeyServiceFactory {
    override val isSupported: Boolean
        get() = platformFactories.any(GlobalHotkeyServiceFactory::isSupported)

    override fun create(bindings: List<GlobalHotkeyBinding>): GlobalHotkeyService {
        val factory = platformFactories.firstOrNull(GlobalHotkeyServiceFactory::isSupported)
            ?: error("Global hotkeys are not supported in the current desktop session.")
        return factory.create(bindings)
    }
}
