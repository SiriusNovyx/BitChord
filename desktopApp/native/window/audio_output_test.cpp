#include "window_jni.cpp"

#include <cstdio>

enum class TestState { Ready, InvalidPadding, InvalidBuffer, Full };
TestState test_state = TestState::Ready;
bool forced_reopen = false;

class TestAudioClient final : public IAudioClient {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID, void**) override { return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE Initialize(AUDCLNT_SHAREMODE, DWORD, REFERENCE_TIME, REFERENCE_TIME,
        const WAVEFORMATEX*, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE GetBufferSize(UINT32* size) override { *size = 8; return S_OK; }
    HRESULT STDMETHODCALLTYPE GetStreamLatency(REFERENCE_TIME*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCurrentPadding(UINT32* padding) override {
        if (test_state == TestState::InvalidPadding) return AUDCLNT_E_DEVICE_INVALIDATED;
        *padding = test_state == TestState::Full ? 8 : 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE IsFormatSupported(AUDCLNT_SHAREMODE, const WAVEFORMATEX*, WAVEFORMATEX**) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE GetMixFormat(WAVEFORMATEX**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetDevicePeriod(REFERENCE_TIME*, REFERENCE_TIME*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Start() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE Stop() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE Reset() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE SetEventHandle(HANDLE) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE GetService(REFIID, void**) override { return E_NOINTERFACE; }
};

class TestRenderClient final : public IAudioRenderClient {
    BYTE buffer[64]{};
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID, void**) override { return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE GetBuffer(UINT32, BYTE** output) override {
        if (test_state == TestState::InvalidBuffer) return AUDCLNT_E_RESOURCES_INVALIDATED;
        *output = buffer;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE ReleaseBuffer(UINT32, DWORD) override { return S_OK; }
};

void recover_client(bool force) {
    forced_reopen = force;
    test_state = TestState::Ready;
    g_audio_reroute = false;
}

int main() {
    TestAudioClient client;
    TestRenderClient render;
    g_audio_client = &client;
    g_audio_render = &render;
    g_audio_rate = 48000;
    g_audio_channels = 2;
    g_audio_bytes_per_sample = 4;
    g_audio_float = true;
    g_audio_buffer_frames = 8;
    const float silence[8]{};
    for (const auto state : {TestState::InvalidPadding, TestState::InvalidBuffer}) {
        test_state = state;
        forced_reopen = false;
        if (write_audio(silence, 8, 1.0f, recover_client) != 32 || !forced_reopen) {
            std::fprintf(stderr, "Invalidated client did not force reopening\n");
            return 1;
        }
    }
    test_state = TestState::Full;
    forced_reopen = false;
    const auto began = GetTickCount64();
    if (write_audio(silence, 8, 1.0f, recover_client) != 0 ||
        GetTickCount64() - began > 5000 || !g_audio_force_reopen || !g_audio_reroute) {
        std::fprintf(stderr, "Stalled buffer did not yield to playback commands\n");
        return 1;
    }
    if (write_audio(silence, 8, 1.0f, recover_client) != 32 || !forced_reopen) {
        std::fprintf(stderr, "Stalled client was not recovered on the next write\n");
        return 1;
    }
    g_audio_client = nullptr;
    g_audio_render = nullptr;
    std::puts("WASAPI: invalidated padding/buffer force reopening; stalled buffer yields and recovers");
    return 0;
}
