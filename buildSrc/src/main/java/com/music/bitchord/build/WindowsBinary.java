package com.music.bitchord.build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public final class WindowsBinary {
    private WindowsBinary() { }

    public static void requireArchitecture(byte[] data, String expected, String label) {
        if (data.length < 64 || data[0] != 'M' || data[1] != 'Z') throw new IllegalArgumentException(label + ": missing DOS header");
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int offset = bytes.getInt(60);
        if (offset < 64 || offset > data.length - 24 || bytes.getInt(offset) != 0x00004550) {
            throw new IllegalArgumentException(label + ": invalid PE header");
        }
        int machine = Short.toUnsignedInt(bytes.getShort(offset + 4));
        int wanted = expected.equals("arm64") ? 0xaa64 : 0x8664;
        if (machine != wanted) throw new IllegalArgumentException(label + ": PE machine 0x" + Integer.toHexString(machine) + ", expected " + expected);
    }

    public static void requireArchitecture(File file, String expected) throws IOException {
        requireArchitecture(Files.readAllBytes(file.toPath()), expected, file.getPath());
    }

    public static int verifyJar(File file, String expected) throws IOException {
        int found = 0;
        try (ZipFile zip = new ZipFile(file)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".dll") && !lower.endsWith(".exe")) continue;
                if (expected.equals("arm64") && lower.contains("lib/amd64-windows")) continue;
                if (expected.equals("x64") && lower.contains("lib/aarch64-windows")) continue;
                // Universal jars keep unused other-platform binaries alongside the selected ones.
                if (expected.equals("arm64") && (name.contains("windows-x86_64/") || name.contains("windows-x64/") || name.contains("win-x64/") || name.contains("win32-x86/") || name.contains("win32-x86-64/"))) continue;
                if (expected.equals("x64") && (name.contains("windows-arm64/") || name.contains("win-aarch64/") || name.contains("win32-aarch64/") || name.contains("win32-x86/"))) continue;
                if (name.contains("/Windows/") && !name.contains(expected.equals("arm64") ? "/Windows/aarch64/" : "/Windows/x86_64/")) continue;
                try (var input = zip.getInputStream(entry)) {
                    requireArchitecture(input.readAllBytes(), expected, file.getName() + "!/" + name);
                }
                found++;
            }
        }
        return found;
    }
}
