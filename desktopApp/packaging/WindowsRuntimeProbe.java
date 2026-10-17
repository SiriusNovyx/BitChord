public final class WindowsRuntimeProbe {
    private static native boolean catchesNativeException();

    public static void main(String[] args) {
        System.load(new java.io.File(args[0]).getAbsolutePath());
        if (!catchesNativeException()) {
            throw new AssertionError("Recoverable Windows exception did not reach its native handler");
        }
        System.out.println("Windows native exception compatibility verified: " + System.getProperty("os.arch"));
    }
}
