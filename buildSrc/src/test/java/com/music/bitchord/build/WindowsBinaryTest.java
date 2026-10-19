package com.music.bitchord.build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WindowsBinaryTest {
    public static void main(String[] args) throws Exception {
        var jar = Files.createTempFile("bitchord-native-architecture-", ".jar");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                add(zip, "lib/amd64-Windows10-clang/jni/socket.dll", 0x8664);
                add(zip, "lib/aarch64-Windows10-clang/jni/socket.dll", 0xaa64);
            }
            for (String arch : new String[] { "arm64", "x64" }) {
                if (WindowsBinary.verifyJar(jar.toFile(), arch) != 1) {
                    throw new AssertionError("wrong native selected for " + arch);
                }
            }
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                add(zip, "lib/amd64-Windows10-clang/jni/socket.dll", 0xaa64);
            }
            try {
                WindowsBinary.verifyJar(jar.toFile(), "x64");
                throw new AssertionError("mislabeled ARM64 native accepted for x64");
            } catch (IllegalArgumentException expected) { }
            System.out.println("WindowsBinary: 3 checks passed");
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void add(ZipOutputStream zip, String name, int machine) throws Exception {
        var bytes = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(0, (byte) 'M').put(1, (byte) 'Z');
        bytes.putInt(60, 80).putInt(80, 0x00004550).putShort(84, (short) machine);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes.array());
        zip.closeEntry();
    }
}
