using System;
using System.IO;
using System.Text;
using System.Windows;
using Gen5WStudio.Models;
using Gen5WStudio.Services;
using Microsoft.Win32;

namespace Gen5WStudio;

public partial class AiContextDialog : Window
{
    private readonly ThemePackModel? activeTheme;

    public AiContextDialog(ThemePackModel? currentTheme)
    {
        activeTheme = currentTheme;
        InitializeComponent();

        IncludeActiveThemeCheck.IsEnabled = activeTheme != null;
        if (activeTheme == null)
        {
            IncludeActiveThemeCheck.ToolTip = "Open or create a theme first to include its layout.json.";
        }

        RebuildPromptText();
    }

    private void RebuildPromptText()
    {
        bool includeActive = IncludeActiveThemeCheck.IsChecked == true && activeTheme != null;
        string prompt = AiThemeContextBuilder.BuildPrompt(activeTheme, includeActive);
        PromptBox.Text = prompt;
        int lines = prompt.Split('\n').Length;
        StatsLabel.Text = $"Studio v{PresetCatalog.StudioVersion} • {lines:N0} lines • {prompt.Length:N0} chars";
    }

    private void OnOptionsChanged(object sender, RoutedEventArgs e)
    {
        if (!IsInitialized) return;
        RebuildPromptText();
        CopyStatusText.Text = "";
    }

    private void OnCopyClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(PromptBox.Text ?? "");
            CopyStatusText.Text = "✓ Copied full AI Context prompt to clipboard!";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Could not copy to clipboard:\n\n" + ex.Message,
                "Clipboard Error", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void OnSaveMarkdownClick(object sender, RoutedEventArgs e)
    {
        var dlg = new SaveFileDialog
        {
            Title = "Save Gen5W AI Theme Builder Context",
            Filter = "Markdown Document (*.md)|*.md|Text File (*.txt)|*.txt",
            FileName = $"Gen5W_AI_Theme_Builder_Context_v{PresetCatalog.StudioVersion}.md"
        };
        if (dlg.ShowDialog(this) != true) return;

        try
        {
            File.WriteAllText(dlg.FileName, PromptBox.Text ?? "", new UTF8Encoding(false));
            CopyStatusText.Text = $"✓ Saved to {Path.GetFileName(dlg.FileName)}";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Save Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
}
