package com.music.bitchord.build;

public final class DesktopTargetTest {
    public static void main(String[] args) throws Exception {
        WindowsBinaryTest.main(args);
        expect("arm64", DesktopTarget.architecture("Windows 11", "amd64", "ARM64", null));
        expect("x64", DesktopTarget.architecture("Windows 11", "amd64", "ARM64", "x64"));
        expect("arm64", DesktopTarget.architecture("Windows 11", "aarch64", "", null));
        expect("x64", DesktopTarget.architecture("Linux", "amd64", "ARM64", null));
        expect("arm64", DesktopTarget.architecture("Mac OS X", "aarch64", "", null));
        try {
            DesktopTarget.architecture("Windows 11", "amd64", "ARM64", "mips");
            throw new AssertionError("invalid target was accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("DesktopTarget: 6 checks passed");
    }
    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
