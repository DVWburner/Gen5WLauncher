using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

/// <summary>
/// Persists creator authentication sessions encrypted with per-user Windows DPAPI,
/// strictly scoped by normalized marketplace origin (https://host[:port]).
/// </summary>
public static class StoreAuthStorage
{
    private const int CryptProtectUiForbidden = 0x1;
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Gen5WStudio.ThemeStore.Auth.v1");
    private static readonly object FileLock = new();
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public static string DefaultStoragePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Gen5WStudio",
        "store_auth.dat");

    public static string NormalizeOrigin(string? baseUrl)
    {
        Uri uri = ThemeStoreClient.NormalizeAndValidateBaseUri(baseUrl);
        return uri.GetLeftPart(UriPartial.Authority).ToLowerInvariant();
    }

    public static StoredStoreSession? LoadSession(string? baseUrl, string? storageFilePath = null)
    {
        string origin;
        try
        {
            origin = NormalizeOrigin(baseUrl);
        }
        catch
        {
            return null;
        }

        lock (FileLock)
        {
            var map = ReadMapNoThrow(ResolvePath(storageFilePath));
            return map.TryGetValue(origin, out var session) ? session : null;
        }
    }

    public static void SaveSession(string? baseUrl, StoredStoreSession session, string? storageFilePath = null)
    {
        if (session == null || string.IsNullOrWhiteSpace(session.Token))
            throw new ArgumentException("Session token must not be empty.", nameof(session));

        string origin = NormalizeOrigin(baseUrl);
        session.BaseOrigin = origin;
        string path = ResolvePath(storageFilePath);

        lock (FileLock)
        {
            var map = ReadMapNoThrow(path);
            map[origin] = session;
            WriteMapAtomic(path, map);
        }
    }

    public static void ClearSession(string? baseUrl, string? storageFilePath = null)
    {
        string origin;
        try
        {
            origin = NormalizeOrigin(baseUrl);
        }
        catch
        {
            return;
        }

        string path = ResolvePath(storageFilePath);
        lock (FileLock)
        {
            if (!File.Exists(path)) return;
            var map = ReadMapNoThrow(path);
            if (!map.Remove(origin)) return;

            if (map.Count == 0)
            {
                try { File.Delete(path); } catch { }
            }
            else
            {
                WriteMapAtomic(path, map);
            }
        }
    }

    public static byte[] ProtectBytes(byte[] plainBytes)
    {
        if (plainBytes == null || plainBytes.Length == 0) return Array.Empty<byte>();
        DATA_BLOB inBlob = default;
        DATA_BLOB entropyBlob = default;
        DATA_BLOB outBlob = default;
        try
        {
            inBlob = AllocBlob(plainBytes);
            entropyBlob = AllocBlob(Entropy);
            if (!CryptProtectData(ref inBlob, "Gen5WStudioStoreAuth", ref entropyBlob, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, ref outBlob))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "DPAPI CryptProtectData failed.");

            byte[] encrypted = new byte[outBlob.cbData];
            if (outBlob.cbData > 0)
                Marshal.Copy(outBlob.pbData, encrypted, 0, outBlob.cbData);
            return encrypted;
        }
        finally
        {
            FreeHGlobalBlob(ref inBlob);
            FreeHGlobalBlob(ref entropyBlob);
            if (outBlob.pbData != IntPtr.Zero) LocalFree(outBlob.pbData);
        }
    }

    public static byte[] UnprotectBytes(byte[] encryptedBytes)
    {
        if (encryptedBytes == null || encryptedBytes.Length == 0) return Array.Empty<byte>();
        DATA_BLOB inBlob = default;
        DATA_BLOB entropyBlob = default;
        DATA_BLOB outBlob = default;
        try
        {
            inBlob = AllocBlob(encryptedBytes);
            entropyBlob = AllocBlob(Entropy);
            if (!CryptUnprotectData(ref inBlob, IntPtr.Zero, ref entropyBlob, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, ref outBlob))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "DPAPI CryptUnprotectData failed.");

            byte[] plain = new byte[outBlob.cbData];
            if (outBlob.cbData > 0)
                Marshal.Copy(outBlob.pbData, plain, 0, outBlob.cbData);
            return plain;
        }
        finally
        {
            FreeHGlobalBlob(ref inBlob);
            FreeHGlobalBlob(ref entropyBlob);
            if (outBlob.pbData != IntPtr.Zero) LocalFree(outBlob.pbData);
        }
    }

    private static string ResolvePath(string? overridePath) =>
        string.IsNullOrWhiteSpace(overridePath) ? DefaultStoragePath : overridePath.Trim();

    private static Dictionary<string, StoredStoreSession> ReadMapNoThrow(string path)
    {
        var result = new Dictionary<string, StoredStoreSession>(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (!File.Exists(path)) return result;
            byte[] encrypted = File.ReadAllBytes(path);
            if (encrypted.Length == 0) return result;
            byte[] plain = UnprotectBytes(encrypted);
            var decoded = JsonSerializer.Deserialize<Dictionary<string, StoredStoreSession>>(plain, JsonOpts);
            if (decoded != null)
            {
                foreach (var kv in decoded)
                {
                    if (!string.IsNullOrWhiteSpace(kv.Key) && kv.Value != null)
                        result[kv.Key.ToLowerInvariant()] = kv.Value;
                }
            }
        }
        catch
        {
            // Ignore corrupt / other-user unreadable store file gracefully
        }
        return result;
    }

    private static void WriteMapAtomic(string path, Dictionary<string, StoredStoreSession> map)
    {
        string? dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(dir)) Directory.CreateDirectory(dir);
        byte[] plain = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(map, JsonOpts));
        byte[] encrypted = ProtectBytes(plain);
        string tempPath = path + ".tmp_" + Guid.NewGuid().ToString("N")[..6];
        try
        {
            File.WriteAllBytes(tempPath, encrypted);
            File.Move(tempPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                try { File.Delete(tempPath); } catch { }
            }
        }
    }

    private static DATA_BLOB AllocBlob(byte[] bytes)
    {
        if (bytes.Length == 0) return new DATA_BLOB { cbData = 0, pbData = IntPtr.Zero };
        IntPtr ptr = Marshal.AllocHGlobal(bytes.Length);
        Marshal.Copy(bytes, 0, ptr, bytes.Length);
        return new DATA_BLOB { cbData = bytes.Length, pbData = ptr };
    }

    private static void FreeHGlobalBlob(ref DATA_BLOB blob)
    {
        if (blob.pbData != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(blob.pbData);
            blob.pbData = IntPtr.Zero;
            blob.cbData = 0;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DATA_BLOB
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DATA_BLOB pDataIn,
        string? szDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DATA_BLOB pDataIn,
        IntPtr ppszDataDescr,
        ref DATA_BLOB pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        int dwFlags,
        ref DATA_BLOB pDataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);
}
