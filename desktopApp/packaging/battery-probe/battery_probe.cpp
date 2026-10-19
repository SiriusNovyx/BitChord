#define NOMINMAX
#include <windows.h>
#include <initguid.h>
#include <devguid.h>
#include <setupapi.h>
#include <poclass.h>
#include <iostream>
#include <vector>

int main() {
    HDEVINFO devices = SetupDiGetClassDevsW(&GUID_DEVCLASS_BATTERY, nullptr, nullptr,
        DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (devices == INVALID_HANDLE_VALUE) {
        std::cerr << "enumeration_error=" << GetLastError() << '\n';
        return 1;
    }
    unsigned found = 0;
    std::cout << "[";
    for (DWORD index = 0; index < 100; ++index) {
        SP_DEVICE_INTERFACE_DATA interface_data{};
        interface_data.cbSize = sizeof(interface_data);
        if (!SetupDiEnumDeviceInterfaces(devices, nullptr, &GUID_DEVCLASS_BATTERY,
            index, &interface_data)) {
            if (GetLastError() != ERROR_NO_MORE_ITEMS)
                std::cerr << "interface_error=" << GetLastError() << '\n';
            break;
        }
        DWORD required = 0;
        SetupDiGetDeviceInterfaceDetailW(devices, &interface_data, nullptr, 0, &required, nullptr);
        if (required < sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W) || required > 1024 * 1024) continue;
        std::vector<unsigned char> detail_storage(required);
        auto* detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W*>(detail_storage.data());
        detail->cbSize = sizeof(*detail);
        if (!SetupDiGetDeviceInterfaceDetailW(devices, &interface_data, detail, required,
            nullptr, nullptr)) continue;
        HANDLE battery = CreateFileW(detail->DevicePath, GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (battery == INVALID_HANDLE_VALUE) {
            std::cerr << "open_error=" << GetLastError() << '\n';
            continue;
        }
        DWORD wait = 0, returned = 0;
        BATTERY_QUERY_INFORMATION query{};
        BATTERY_INFORMATION information{};
        bool valid = DeviceIoControl(battery, IOCTL_BATTERY_QUERY_TAG, &wait, sizeof(wait),
            &query.BatteryTag, sizeof(query.BatteryTag), &returned, nullptr) &&
            returned == sizeof(query.BatteryTag) && query.BatteryTag;
        query.InformationLevel = BatteryInformation;
        valid = valid && DeviceIoControl(battery, IOCTL_BATTERY_QUERY_INFORMATION, &query,
            sizeof(query), &information, sizeof(information), &returned, nullptr) &&
            returned >= sizeof(information);
        if (!valid) {
            std::cerr << "information_error=" << GetLastError() << '\n';
            CloseHandle(battery);
            continue;
        }
        BATTERY_WAIT_STATUS request{};
        request.BatteryTag = query.BatteryTag;
        BATTERY_STATUS status{};
        bool status_valid = DeviceIoControl(battery, IOCTL_BATTERY_QUERY_STATUS, &request,
            sizeof(request), &status, sizeof(status), &returned, nullptr) && returned >= sizeof(status);
        if (found++) std::cout << ',';
        std::cout << "{\"capabilities\":" << information.Capabilities
            << ",\"systemBattery\":" << ((information.Capabilities & BATTERY_SYSTEM_BATTERY) ? "true" : "false")
            << ",\"shortTerm\":" << ((information.Capabilities & BATTERY_IS_SHORT_TERM) ? "true" : "false")
            << ",\"relativeUnits\":" << ((information.Capabilities & BATTERY_CAPACITY_RELATIVE) ? "true" : "false")
            << ",\"designedCapacity\":" << information.DesignedCapacity
            << ",\"fullChargedCapacity\":" << information.FullChargedCapacity
            << ",\"statusValid\":" << (status_valid ? "true" : "false");
        if (status_valid) std::cout << ",\"powerState\":" << status.PowerState
            << ",\"capacity\":" << status.Capacity << ",\"voltage\":" << status.Voltage
            << ",\"rate\":" << status.Rate;
        std::cout << '}';
        CloseHandle(battery);
    }
    SetupDiDestroyDeviceInfoList(devices);
    std::cout << "]\n";
    return found ? 0 : 2;
}
