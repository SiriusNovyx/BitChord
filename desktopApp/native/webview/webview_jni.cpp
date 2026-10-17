#include <windows.h>
#include <jni.h>
#include <wrl.h>
#include <shlwapi.h>
#include <WebView2.h>
#include <atomic>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;
namespace {
constexpr UINT COMMAND = WM_APP + 1;
constexpr UINT UPDATE_BOUNDS = WM_APP + 2;
constexpr UINT CLOSE_HOST = WM_APP + 3;
constexpr int CONFIRM = 1001;
constexpr int RESTART = 1002;
JavaVM* vm = nullptr;
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<struct Session>> registry;
std::atomic<jlong> nextId{1};

class JavaAttachment {
    bool detach = false;
public:
    JNIEnv* env = nullptr;
    JavaAttachment() {
        auto result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (result == JNI_EDETACHED) {
            if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK) detach = true;
            else env = nullptr;
        }
    }
    ~JavaAttachment() { if (detach) vm->DetachCurrentThread(); }
};

std::wstring string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const auto* chars = env->GetStringChars(value, nullptr);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

struct Session : std::enable_shared_from_this<Session> {
    jobject owner;
    jmethodID callback = nullptr;
    std::wstring profile, html, url, title;
    bool visible;
    bool initialDocument = true;
    bool initializing = false;
    std::wstring documentSource;
    std::atomic<bool> closing{false};
    std::atomic<HWND> window{nullptr};
    std::thread worker;
    std::mutex queueMutex;
    std::deque<std::function<void(Session&)>> queue;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> web;

    void event(const wchar_t* kind, const std::wstring& value = {}) {
        if (closing) return;
        // COM dispatch runs unattached; the JVM owns this thread only during callbacks.
        JavaAttachment attachment;
        auto* env = attachment.env;
        if (!env) return;
        auto k = env->NewString(reinterpret_cast<const jchar*>(kind), static_cast<jsize>(wcslen(kind)));
        auto v = env->NewString(reinterpret_cast<const jchar*>(value.data()), static_cast<jsize>(value.size()));
        env->CallVoidMethod(owner, callback, k, v);
        env->DeleteLocalRef(k); env->DeleteLocalRef(v);
        // Callback exceptions must not poison the native UI thread.
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    bool enqueue(std::function<void(Session&)> command) {
        std::lock_guard<std::mutex> lock(queueMutex);
        if (closing || queue.size() >= 128) return false;
        queue.push_back(std::move(command));
        if (window.load()) PostMessage(window, COMMAND, 0, 0);
        return true;
    }

    void commands() {
        if (!web || closing) return;
        std::deque<std::function<void(Session&)>> pending;
        { std::lock_guard<std::mutex> lock(queueMutex); pending.swap(queue); }
        for (auto& command : pending) { if (closing) break; command(*this); }
    }

    void bounds() {
        if (!controller) return;
        RECT rect{}; GetClientRect(window, &rect);
        auto scale = GetDpiForWindow(window) / 96.0;
        if (visible) rect.bottom = std::max<LONG>(0, rect.bottom - static_cast<LONG>(44 * scale));
        controller->put_Bounds(rect);
        if (visible) {
            MoveWindow(GetDlgItem(window, CONFIRM), rect.right - static_cast<int>(160 * scale), rect.bottom + static_cast<int>(7 * scale), static_cast<int>(150 * scale), static_cast<int>(30 * scale), TRUE);
            MoveWindow(GetDlgItem(window, RESTART), static_cast<int>(10 * scale), rect.bottom + static_cast<int>(7 * scale), static_cast<int>(110 * scale), static_cast<int>(30 * scale), TRUE);
        }
    }

    void initialize() {
        initializing = true;
        auto weak = weak_from_this();
        auto hr = CreateCoreWebView2EnvironmentWithOptions(nullptr, profile.c_str(), nullptr,
            Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                [weak](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                    auto self = weak.lock(); if (!self) return S_OK;
                    if (self->closing || FAILED(result) || !environment) {
                        self->initializing = false;
                        if (self->closing) PostMessage(self->window, CLOSE_HOST, 0, 0);
                        else self->event(L"error", L"WebView2 environment failed");
                        return S_OK;
                    }
                    auto hr = environment->CreateCoreWebView2Controller(self->window,
                        Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                            [weak](HRESULT result, ICoreWebView2Controller* controller) -> HRESULT {
                                auto self = weak.lock(); if (!self) return S_OK;
                                self->initializing = false;
                                if (self->closing) {
                                    self->controller = controller;
                                    PostMessage(self->window, CLOSE_HOST, 0, 0);
                                    return S_OK;
                                }
                                if (FAILED(result) || !controller) { self->event(L"error", L"WebView2 controller failed"); return S_OK; }
                                self->controller = controller;
                                controller->get_CoreWebView2(&self->web);
                                ComPtr<ICoreWebView2Settings> settings;
                                self->web->get_Settings(&settings);
                                settings->put_AreDevToolsEnabled(FALSE);
                                settings->put_AreDefaultContextMenusEnabled(self->visible);
                                settings->put_IsWebMessageEnabled(!self->visible);
                                EventRegistrationToken token{};
                                self->web->add_WebMessageReceived(Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                    [weak](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                        auto self = weak.lock(); if (!self || self->closing || self->visible) return S_OK;
                                        LPWSTR source = nullptr, json = nullptr;
                                        args->get_Source(&source);
                                        if (source && (wcscmp(source, L"about:blank") == 0 || self->documentSource == source) && SUCCEEDED(args->get_WebMessageAsJson(&json)))
                                            self->event(L"message", json ? json : L"");
                                        CoTaskMemFree(source); CoTaskMemFree(json); return S_OK;
                                    }).Get(), &token);
                                self->web->add_NavigationStarting(Callback<ICoreWebView2NavigationStartingEventHandler>(
                                    [weak](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                                        auto self = weak.lock(); if (!self || self->closing) return S_OK;
                                        LPWSTR uri = nullptr; args->get_Uri(&uri);
                                        std::wstring target = uri ? uri : L""; CoTaskMemFree(uri);
                                        // BotGuard stays in its host document. Sign-in permits HTTPS only.
                                        bool permitted = self->visible ? target.rfind(L"https://", 0) == 0 :
                                            self->initialDocument && (target == L"about:blank" || target.rfind(L"data:text/html;charset=utf-8;base64,", 0) == 0);
                                        if (!self->visible && permitted) {
                                            self->initialDocument = false;
                                            self->documentSource = target;
                                        }
                                        if (!permitted) args->put_Cancel(TRUE);
                                        return S_OK;
                                    }).Get(), &token);
                                self->web->add_NavigationCompleted(Callback<ICoreWebView2NavigationCompletedEventHandler>(
                                    [weak](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                                        auto self = weak.lock(); if (!self || self->closing) return S_OK;
                                        BOOL success = FALSE; args->get_IsSuccess(&success);
                                        if (success) {
                                            if (self->visible) {
                                                LPWSTR source = nullptr; self->web->get_Source(&source);
                                                wchar_t host[2048]{};
                                                DWORD length = 2048;
                                                if (source && SUCCEEDED(UrlGetPartW(source, host, &length, URL_PART_HOSTNAME, 0))) {
                                                    SetWindowText(self->window, (self->title + L" — " + host).c_str());
                                                    auto expected = self->url.rfind(L"https://accounts.spotify.com/", 0) == 0 ? L"open.spotify.com" : L"music.youtube.com";
                                                    EnableWindow(GetDlgItem(self->window, CONFIRM), wcscmp(host, expected) == 0);
                                                }
                                                self->event(L"navigated", source ? source : L""); CoTaskMemFree(source);
                                            } else self->event(L"loaded");
                                        } else {
                                            COREWEBVIEW2_WEB_ERROR_STATUS status{};
                                            args->get_WebErrorStatus(&status);
                                            self->event(L"error", L"Browser navigation failed (status " + std::to_wstring(status) + L")");
                                        }
                                        return S_OK;
                                    }).Get(), &token);
                                self->web->add_NewWindowRequested(Callback<ICoreWebView2NewWindowRequestedEventHandler>(
                                    [weak](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* args) -> HRESULT {
                                        auto self = weak.lock(); if (!self || self->closing) return S_OK;
                                        args->put_Handled(TRUE);
                                        if (self->visible) {
                                            LPWSTR uri = nullptr; args->get_Uri(&uri);
                                            if (uri && wcsncmp(uri, L"https://", 8) == 0) self->web->Navigate(uri);
                                            CoTaskMemFree(uri);
                                        }
                                        return S_OK;
                                    }).Get(), &token);
                                self->bounds(); controller->put_IsVisible(self->visible);
                                if (self->visible) self->web->Navigate(self->url.c_str());
                                else self->web->NavigateToString(self->html.c_str());
                                self->commands(); return S_OK;
                            }).Get());
                    if (FAILED(hr)) { self->initializing = false; self->event(L"error", L"Browser controller creation failed"); }
                    return S_OK;
                }).Get());
        if (FAILED(hr)) { initializing = false; event(L"error", L"WebView2 runtime could not initialize"); }
    }

    void cookies(const std::wstring& target) {
        if (target != L"https://music.youtube.com/" && target != L"https://open.spotify.com/") return;
        ComPtr<ICoreWebView2_2> version2;
        if (FAILED(web.As(&version2))) { event(L"error", L"WebView2 cookie API unavailable"); return; }
        ComPtr<ICoreWebView2CookieManager> manager; version2->get_CookieManager(&manager);
        auto weak = weak_from_this();
        manager->GetCookies(target.c_str(), Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [weak](HRESULT result, ICoreWebView2CookieList* list) -> HRESULT {
                auto self = weak.lock(); if (!self || self->closing) return S_OK;
                if (FAILED(result) || !list) { self->event(L"error", L"Cookie lookup failed"); return S_OK; }
                UINT count = 0; list->get_Count(&count);
                std::wstring header;
                for (UINT i = 0; i < count; ++i) {
                    ComPtr<ICoreWebView2Cookie> cookie; list->GetValueAtIndex(i, &cookie);
                    LPWSTR name = nullptr, value = nullptr;
                    cookie->get_Name(&name); cookie->get_Value(&value);
                    std::wstring n = name ? name : L"", v = value ? value : L"";
                    CoTaskMemFree(name); CoTaskMemFree(value);
                    if (n.empty() || n.find_first_of(L";=\r\n") != std::wstring::npos || v.find_first_of(L";\r\n") != std::wstring::npos) continue;
                    if (!header.empty()) header += L"; ";
                    header += n + L"=" + v;
                }
                self->event(L"cookies", header); return S_OK;
            }).Get());
    }

    static LRESULT CALLBACK procedure(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
        auto* self = reinterpret_cast<Session*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
        if (message == WM_NCCREATE) {
            self = reinterpret_cast<Session*>(reinterpret_cast<CREATESTRUCT*>(l)->lpCreateParams);
            SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        }
        if (self) switch (message) {
            case COMMAND: self->commands(); return 0;
            case UPDATE_BOUNDS: if (!self->closing) self->bounds(); return 0;
            case WM_SIZE: PostMessage(hwnd, UPDATE_BOUNDS, 0, 0); return 0;
            case WM_DPICHANGED: {
                auto* rect = reinterpret_cast<RECT*>(l);
                SetWindowPos(hwnd, nullptr, rect->left, rect->top, rect->right - rect->left, rect->bottom - rect->top, SWP_NOZORDER | SWP_NOACTIVATE);
                PostMessage(hwnd, UPDATE_BOUNDS, 0, 0); return 0;
            }
            case WM_COMMAND:
                if (LOWORD(w) == CONFIRM) self->event(L"confirm");
                if (LOWORD(w) == RESTART) self->event(L"restart");
                return 0;
            case WM_CLOSE:
                self->event(L"closed"); self->closing = true;
                PostMessage(hwnd, CLOSE_HOST, 0, 0); return 0;
            case CLOSE_HOST:
                // Drain environment/controller creation before releasing the STA apartment.
                if (self->initializing) return 0;
                if (self->controller) self->controller->Close();
                self->web.Reset(); self->controller.Reset();
                DestroyWindow(hwnd); return 0;
            case WM_DESTROY: PostQuitMessage(0); return 0;
        }
        return DefWindowProc(hwnd, message, w, l);
    }

    void run() {
        auto hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        if (FAILED(hr)) { event(L"error", L"Browser COM initialization failed"); }
        else {
            WNDCLASS wc{}; wc.lpfnWndProc = procedure; wc.hInstance = GetModuleHandle(nullptr); wc.lpszClassName = L"BitChordWebView2";
            wc.hCursor = LoadCursor(nullptr, IDC_ARROW); wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
            RegisterClass(&wc);
            auto hwnd = CreateWindowEx(0, wc.lpszClassName, title.c_str(), WS_OVERLAPPEDWINDOW,
                CW_USEDEFAULT, CW_USEDEFAULT, 760, 920, nullptr, nullptr, wc.hInstance, this);
            window = hwnd;
            if (!hwnd) event(L"error", L"Browser window creation failed");
            else {
                if (visible) {
                    CreateWindow(L"BUTTON", L"Use this profile", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 0, 0, 0, 0, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(CONFIRM)), wc.hInstance, nullptr);
                    EnableWindow(GetDlgItem(hwnd, CONFIRM), FALSE);
                    CreateWindow(L"BUTTON", L"Start over", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 0, 0, 0, 0, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(RESTART)), wc.hInstance, nullptr);
                    ShowWindow(hwnd, SW_SHOW);
                }
                if (closing) PostMessage(hwnd, WM_CLOSE, 0, 0); else initialize();
                MSG message{};
                while (GetMessage(&message, nullptr, 0, 0) > 0) { TranslateMessage(&message); DispatchMessage(&message); }
                window = nullptr;
                web.Reset(); controller.Reset();
            }
            CoUninitialize();
        }
        closing = true;
        JavaAttachment attachment;
        if (attachment.env) attachment.env->DeleteGlobalRef(owner);
        owner = nullptr;
    }

    void stop() {
        closing = true;
        if (window.load()) PostMessage(window, WM_CLOSE, 0, 0);
        if (worker.joinable() && worker.get_id() != std::this_thread::get_id()) worker.join();
    }
};

std::shared_ptr<Session> find(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = registry.find(id); return it == registry.end() ? nullptr : it->second;
}
}

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* value, void*) {
    vm = value;
    return JNI_VERSION_1_8;
}
JNIEXPORT jboolean JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_runtimeAvailableNative(JNIEnv*, jobject) {
    LPWSTR version = nullptr;
    auto hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    CoTaskMemFree(version); return SUCCEEDED(hr);
}
JNIEXPORT jlong JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_createNative(JNIEnv* env, jobject, jobject owner, jstring profile, jstring html, jstring url, jboolean visible, jstring title) {
    auto session = std::make_shared<Session>();
    auto type = env->GetObjectClass(owner);
    session->callback = env->GetMethodID(type, "event", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(type);
    if (!session->callback) return 0;
    session->owner = env->NewGlobalRef(owner);
    if (!session->owner) return 0;
    session->profile = string(env, profile); session->html = string(env, html); session->url = string(env, url);
    session->visible = visible; session->title = string(env, title);
    auto id = nextId++;
    { std::lock_guard<std::mutex> lock(registryMutex); registry.emplace(id, session); }
    session->worker = std::thread([session] { session->run(); });
    return id;
}
JNIEXPORT jboolean JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_executeNative(JNIEnv* env, jobject, jlong id, jstring script) {
    auto session = find(id); auto source = string(env, script);
    if (source.size() > 2 * 1024 * 1024) return false;
    return session && session->enqueue([source](Session& s) {
        auto weak = s.weak_from_this();
        s.web->ExecuteScript(source.c_str(), Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
            [weak](HRESULT hr, LPCWSTR) -> HRESULT {
                if (auto session = weak.lock(); session && FAILED(hr)) session->event(L"error", L"JavaScript execution failed");
                return S_OK;
            }).Get());
    });
}
JNIEXPORT jboolean JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_cookiesNative(JNIEnv* env, jobject, jlong id, jstring url) {
    auto session = find(id); auto target = string(env, url);
    return session && session->enqueue([target](Session& s) { s.cookies(target); });
}
JNIEXPORT void JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_closeNative(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Session> session;
    { std::lock_guard<std::mutex> lock(registryMutex);
      auto it = registry.find(id); if (it != registry.end()) { session = it->second; registry.erase(it); } }
    if (session) session->stop();
}
JNIEXPORT void JNICALL Java_com_music_bitchord_desktop_DesktopWebView2_closeAllNative(JNIEnv*, jobject) {
    std::map<jlong, std::shared_ptr<Session>> pending;
    { std::lock_guard<std::mutex> lock(registryMutex); pending.swap(registry); }
    for (auto& entry : pending) entry.second->stop();
}
}
