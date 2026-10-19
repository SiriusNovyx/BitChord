#include <windows.h>
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL Java_WindowsRuntimeProbe_catchesNativeException(JNIEnv*, jclass) {
    // UI Automation can raise this recoverable RPC exception inside WebView2.
    // A process-wide JVM handler must let the native handler receive it.
    __try {
        RaiseException(0x8001010d, 0, 0, nullptr);
    } __except(GetExceptionCode() == 0x8001010d ? EXCEPTION_EXECUTE_HANDLER : EXCEPTION_CONTINUE_SEARCH) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}
