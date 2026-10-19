package com.music.bitchord.desktop

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess

internal object DesktopModuleRuntime {
    fun context(): Context {
        if (DesktopBrowserPlatform.usesWebView2) {
            // GraalJS 24.1.2 has no Windows ARM64 libtruffleattach. Its supported
            // Java interpreter preserves module execution without native host access.
            System.setProperty("truffle.UseFallbackRuntime", "true")
        }
        return Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .allowIO(false)
            .allowNativeAccess(false)
            .allowCreateThread(false)
            .build()
    }
}
