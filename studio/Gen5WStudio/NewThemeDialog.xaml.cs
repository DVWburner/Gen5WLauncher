using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Gen5WStudio.Services;

namespace Gen5WStudio;

public partial class NewThemeDialog : Window
{
    private bool packIdManuallyEdited;

    public string ThemeName => ThemeNameBox.Text.Trim();
    public string PackId => PresetCatalog.SanitizePackId(PackIdBox.Text);
    public string AuthorName => AuthorBox.Text.Trim();
    public string StarterStyle => (StarterStyleCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "blank_freeform";
    public string SelectedSpeedUnit => (SpeedUnitCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "mph";
    public string SelectedTempUnit => (TempUnitCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "F";
    public bool ShowSystemBar => ShowSystemBarCheck.IsChecked == true;
    public bool StartNewCleanPack => StartCleanPackRadio.IsChecked == true;

    public NewThemeDialog() : this(defaultStartCleanPack: true, canAddToCurrentPack: false, hasOpenTheme: false)
    {
    }

    public NewThemeDialog(bool defaultStartCleanPack, bool canAddToCurrentPack, bool hasOpenTheme = false)
    {
        InitializeComponent();
        if (!string.IsNullOrWhiteSpace(Environment.UserName))
        {
            AuthorBox.Text = Environment.UserName;
        }
        if (!hasOpenTheme)
        {
            StarterStyleCombo.Items.Remove(CloneCurrentComboItem);
        }
        StartCleanPackRadio.IsChecked = defaultStartCleanPack;
        AddToPackRadio.IsChecked = !defaultStartCleanPack;
        AddToPackRadio.IsEnabled = canAddToCurrentPack;
        if (!canAddToCurrentPack)
        {
            AddToPackRadio.Content = "Add to currently open Pack (only available inside a custom Pack)";
            StartCleanPackRadio.IsChecked = true;
        }

        Loaded += (_, _) =>
        {
            ThemeNameBox.Focus();
            ThemeNameBox.SelectAll();
        };
    }

    private void OnThemeNameChanged(object sender, TextChangedEventArgs e)
    {
        if (packIdManuallyEdited || PackIdBox == null) return;
        PackIdBox.Text = PresetCatalog.SanitizePackId(ThemeNameBox.Text);
    }

    private void OnPackIdKeyUp(object sender, KeyEventArgs e)
    {
        packIdManuallyEdited = !string.IsNullOrWhiteSpace(PackIdBox.Text);
    }

    private void OnCreateClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(ThemeNameBox.Text))
        {
            MessageBox.Show(this, "Please enter a theme name.", "Create New Theme", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        DialogResult = true;
    }
}
