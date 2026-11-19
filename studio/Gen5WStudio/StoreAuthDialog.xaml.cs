using System;
using System.Windows;
using System.Windows.Input;
using Gen5WStudio.Models;
using Gen5WStudio.Services;

namespace Gen5WStudio;

public partial class StoreAuthDialog : Window
{
    private readonly string _baseUrl;

    public StoredStoreSession? AuthenticatedSession { get; private set; }

    public StoreAuthDialog(string baseUrl, bool startOnRegisterTab = false, string? bannerMessage = null)
    {
        InitializeComponent();
        _baseUrl = string.IsNullOrWhiteSpace(baseUrl) ? ThemeStoreClient.DefaultBaseUrl : baseUrl.Trim();

        try
        {
            OriginText.Text = "Server: " + StoreAuthStorage.NormalizeOrigin(_baseUrl);
        }
        catch
        {
            OriginText.Text = "Server: " + _baseUrl;
        }

        if (!string.IsNullOrWhiteSpace(bannerMessage))
        {
            BannerText.Text = bannerMessage.Trim();
            BannerBorder.Visibility = Visibility.Visible;
        }

        if (startOnRegisterTab)
            AuthTabs.SelectedIndex = 1;

        Loaded += (_, _) =>
        {
            if (AuthTabs.SelectedIndex == 0) LoginUsernameBox.Focus();
            else RegUsernameBox.Focus();
        };
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBorder.Visibility = Visibility.Visible;
    }

    private void ClearError()
    {
        ErrorText.Text = "";
        ErrorBorder.Visibility = Visibility.Collapsed;
    }

    private void SetBusy(bool busy, string status = "")
    {
        SignInSubmitBtn.IsEnabled = !busy;
        RegisterSubmitBtn.IsEnabled = !busy;
        AuthTabs.IsEnabled = !busy;
        BusyStatusText.Text = status;
    }

    private void CompleteSuccess(StoreAuthResponse resp, string fallbackUsername)
    {
        var session = new StoredStoreSession
        {
            Username = !string.IsNullOrWhiteSpace(resp.Account?.Username) ? resp.Account!.Username : fallbackUsername,
            CreatedAt = resp.Account?.CreatedAt,
            Token = resp.Token!,
            ExpiresAt = resp.ExpiresAt
        };
        StoreAuthStorage.SaveSession(_baseUrl, session);
        AuthenticatedSession = session;

        LoginPasswordBox.Clear();
        RegPasswordBox.Clear();
        RegConfirmPasswordBox.Clear();

        DialogResult = true;
        Close();
    }

    private async void OnSignInSubmitClick(object sender, RoutedEventArgs e)
    {
        ClearError();
        string username = (LoginUsernameBox.Text ?? "").Trim();
        string password = LoginPasswordBox.Password ?? "";

        if (string.IsNullOrWhiteSpace(username))
        {
            ShowError("Please enter your username.");
            LoginUsernameBox.Focus();
            return;
        }
        if (string.IsNullOrEmpty(password))
        {
            ShowError("Please enter your password.");
            LoginPasswordBox.Focus();
            return;
        }

        SetBusy(true, "Signing in...");
        try
        {
            var resp = await ThemeStoreClient.LoginAsync(_baseUrl, username, password);
            CompleteSuccess(resp, username);
        }
        catch (ThemeStoreApiException apiEx)
        {
            ShowError(apiEx.FormatFriendlyMessage());
        }
        catch (Exception ex)
        {
            ShowError("Could not reach Theme Store: " + ex.Message);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void OnRegisterSubmitClick(object sender, RoutedEventArgs e)
    {
        ClearError();
        string username = (RegUsernameBox.Text ?? "").Trim();
        string password = RegPasswordBox.Password ?? "";
        string confirm = RegConfirmPasswordBox.Password ?? "";

        if (!ThemeStoreClient.ValidateUsername(username, out string userErr))
        {
            ShowError(userErr);
            RegUsernameBox.Focus();
            return;
        }
        if (!ThemeStoreClient.ValidatePassword(password, out string passErr))
        {
            ShowError(passErr);
            RegPasswordBox.Focus();
            return;
        }
        if (!string.Equals(password, confirm, StringComparison.Ordinal))
        {
            ShowError("Passwords do not match.");
            RegConfirmPasswordBox.Focus();
            return;
        }

        SetBusy(true, "Creating account...");
        try
        {
            var resp = await ThemeStoreClient.RegisterAsync(_baseUrl, username, password);
            CompleteSuccess(resp, username);
        }
        catch (ThemeStoreApiException apiEx)
        {
            ShowError(apiEx.FormatFriendlyMessage());
        }
        catch (Exception ex)
        {
            ShowError("Could not reach Theme Store: " + ex.Message);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void OnLoginKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
            OnSignInSubmitClick(sender, new RoutedEventArgs());
    }

    private void OnRegisterKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
            OnRegisterSubmitClick(sender, new RoutedEventArgs());
    }

    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        LoginPasswordBox.Clear();
        RegPasswordBox.Clear();
        RegConfirmPasswordBox.Clear();
        DialogResult = false;
        Close();
    }
}
