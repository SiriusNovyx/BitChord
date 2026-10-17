package com.music.bitchord.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DesktopTarget {
    private DesktopTarget() { }
    public static String architecture(String os, String jvmArch, String nativeArch, String override) {
        String selected = override;
        if (selected == null || selected.isBlank()) {
            selected = os.toLowerCase(Locale.ROOT).contains("windows") && nativeArch != null && !nativeArch.isBlank()
                ? nativeArch : jvmArch;
        }
        switch (selected.trim().toLowerCase(Locale.ROOT)) {
            case "arm64": case "aarch64": return "arm64";
            case "x64": case "amd64": case "x86_64": return "x64";
            default: throw new IllegalArgumentException("Unsupported desktop architecture: " + selected + "; use bitchord.arch=arm64 or x64");
        }
    }

    public static String windowsNativeArchitecture() {
        String nativeArch = System.getenv("PROCESSOR_ARCHITEW6432");
        if (nativeArch != null && !nativeArch.isBlank()) return nativeArch;
        // The machine-wide value is independent of the architecture of the Gradle JVM.
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            try {
                Process process = new ProcessBuilder(new File(systemRoot, "System32/reg.exe").getPath(),
                    "query", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
                    "/v", "PROCESSOR_ARCHITECTURE").redirectErrorStream(true).start();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                } else if (process.exitValue() == 0) {
                    String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : result.split("\\R")) {
                        if (line.contains("PROCESSOR_ARCHITECTURE") && line.contains("REG_SZ")) {
                            return line.substring(line.indexOf("REG_SZ") + 6).trim();
                        }
                    }
                }
            } catch (Exception ignored) { /* Explicit bitchord.arch remains available on restricted hosts. */ }
        }
        return System.getenv("PROCESSOR_ARCHITECTURE");
    }
}
