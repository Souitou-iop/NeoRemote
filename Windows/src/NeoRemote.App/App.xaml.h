#pragma once

#include "App.g.h"
#include "MainWindow.xaml.h"

namespace winrt::NeoRemote::App::implementation {

struct App : AppT<App> {
    App();
    void OnLaunched(Microsoft::UI::Xaml::LaunchActivatedEventArgs const&);

private:
    winrt::NeoRemote::App::MainWindow window_{nullptr};
};

} // namespace winrt::NeoRemote::App::implementation
