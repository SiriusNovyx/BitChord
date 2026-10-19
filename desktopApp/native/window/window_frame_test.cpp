#include "window_jni.cpp"

#include <cstdio>
#include <iterator>
#include <future>
#include <chrono>
#include <cstdlib>

namespace {
bool expect_hit(HWND window, POINT point, LRESULT expected, const char* label) {
    const LRESULT actual = SendMessageW(window, WM_NCHITTEST, 0, MAKELPARAM(point.x, point.y));
    if (actual == expected) return true;
    std::fprintf(stderr, "%s: hit %lld, expected %lld\n", label,
        static_cast<long long>(actual), static_cast<long long>(expected));
    return false;
}
}

int main() {
    const HINSTANCE instance = GetModuleHandleW(nullptr);
    WNDCLASSW frame_class{};
    frame_class.hInstance = instance;
    frame_class.lpfnWndProc = DefWindowProcW;
    frame_class.lpszClassName = L"BitChordWindowFrameTest";
    WNDCLASSW canvas_class = frame_class;
    canvas_class.lpszClassName = L"SunAwtCanvas";
    if (!RegisterClassW(&frame_class) || !RegisterClassW(&canvas_class)) return 2;
    HWND frame = CreateWindowExW(0, frame_class.lpszClassName, L"BitChord frame test",
        WS_OVERLAPPEDWINDOW, -1000, -1000, 800, 600, nullptr, nullptr, instance, nullptr);
    HWND canvas = CreateWindowExW(0, canvas_class.lpszClassName, L"", WS_CHILD,
        0, 0, 800, 600, frame, nullptr, instance, nullptr);
    if (!frame || !canvas || !install_frame(frame)) return 2;
    RECT bounds{};
    GetWindowRect(frame, &bounds);
    const int cx = (bounds.left + bounds.right) / 2;
    const int cy = (bounds.top + bounds.bottom) / 2;
    const POINT points[] = {
        {bounds.left + 1, cy}, {bounds.right - 2, cy},
        {cx, bounds.top + 1}, {cx, bounds.bottom - 2},
        {bounds.left + 1, bounds.top + 1}, {bounds.right - 2, bounds.top + 1},
        {bounds.left + 1, bounds.bottom - 2}, {bounds.right - 2, bounds.bottom - 2},
    };
    const LRESULT expected[] = {HTLEFT, HTRIGHT, HTTOP, HTBOTTOM,
        HTTOPLEFT, HTTOPRIGHT, HTBOTTOMLEFT, HTBOTTOMRIGHT};
    bool passed = true;
    for (size_t index = 0; index < std::size(points); ++index) {
        passed &= expect_hit(frame, points[index], expected[index], "frame edge");
        passed &= expect_hit(canvas, points[index], HTTRANSPARENT, "canvas edge");
    }
    passed &= expect_hit(canvas, {cx, cy}, HTCLIENT, "canvas center");
    HWND replacement = CreateWindowExW(0, canvas_class.lpszClassName, L"", WS_CHILD,
        0, 0, 800, 600, frame, nullptr, instance, nullptr);
    passed &= replacement != nullptr;
    if (replacement) {
        passed &= expect_hit(replacement, points[0], HTTRANSPARENT, "new canvas edge");
        passed &= expect_hit(replacement, {cx, cy}, HTCLIENT, "new canvas center");
    }
    DestroyWindow(frame);
    {
        std::lock_guard<std::mutex> audio_busy(g_audio_mutex);
        auto discovery = std::async(std::launch::async, [] { return discover_device_values(); });
        auto metadata = std::async(std::launch::async, [] { return device_name_snapshot(); });
        if (discovery.wait_for(std::chrono::seconds(5)) != std::future_status::ready ||
            metadata.wait_for(std::chrono::seconds(1)) != std::future_status::ready) {
            std::fprintf(stderr, "Device discovery or UI metadata waited for the playback lock\n");
            std::_Exit(1);
        }
        passed &= discovery.get().size() % 2 == 0;
        passed &= metadata.get().empty();
    }
    if (passed) std::puts("Window frame: eight edges, client center, and recreated canvas passed");
    if (passed) std::puts("Audio discovery and UI metadata remain independent of the playback lock");
    return passed ? 0 : 1;
}
