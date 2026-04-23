#include "pch.h"
#include "App.xaml.h"

#include <winrt/Microsoft.UI.Xaml.h>

namespace winrt::NeoRemote::App::implementation {

App::App()
{
    InitializeComponent();
}

void App::OnLaunched(Microsoft::UI::Xaml::LaunchActivatedEventArgs const&)
{
    window_ = winrt::make<winrt::NeoRemote::App::implementation::MainWindow>();
    window_.Activate();
}

} // namespace winrt::NeoRemote::App::implementation
