using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace PCRemote
{
    /// Finds installed apps by name (from the Start menu and Desktop shortcuts) and gets their real icons.
    static class Apps
    {
        public sealed class App { public string Name; public string Path; public string Norm; }

        static List<App> cache;
        static DateTime cacheTime;
        static readonly object Lock = new object();

        public static List<App> List()
        {
            lock (Lock)
            {
                if (cache != null && (DateTime.Now - cacheTime).TotalSeconds < 60) return cache;
                var found = new Dictionary<string, App>(StringComparer.OrdinalIgnoreCase);
                var dirs = new[] {
                    Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms),
                    Environment.GetFolderPath(Environment.SpecialFolder.Programs),
                    Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory),
                    Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                };
                foreach (var d in dirs)
                {
                    if (string.IsNullOrEmpty(d) || !Directory.Exists(d)) continue;
                    foreach (var f in SafeFiles(d))
                    {
                        string ext = System.IO.Path.GetExtension(f).ToLowerInvariant();
                        if (ext != ".lnk" && ext != ".url") continue;
                        string name = System.IO.Path.GetFileNameWithoutExtension(f);
                        string n = name.ToLowerInvariant();
                        if (n.Contains("uninstall") || n.Contains("readme") || n.Contains("help") || n.Contains("website") || n.Contains("license")) continue;
                        if (!found.ContainsKey(name)) found[name] = new App { Name = name, Path = f, Norm = Normalize(name) };
                    }
                }
                cache = found.Values.OrderBy(a => a.Name, StringComparer.OrdinalIgnoreCase).ToList();
                cacheTime = DateTime.Now;
                return cache;
            }
        }

        static IEnumerable<string> SafeFiles(string dir)
        {
            var stack = new Stack<string>();
            stack.Push(dir);
            while (stack.Count > 0)
            {
                string d = stack.Pop();
                string[] files = new string[0], subs = new string[0];
                try { files = Directory.GetFiles(d); subs = Directory.GetDirectories(d); } catch { }
                foreach (var f in files) yield return f;
                foreach (var s in subs) stack.Push(s);
            }
        }

        public static string Normalize(string s)
        {
            s = s.ToLowerInvariant().Replace("™", "").Replace("®", "").Replace("©", "");
            s = Regex.Replace(s, "[^a-z0-9]+", " ");
            return s.Trim();
        }

        /// Best match for a name like "FC 27" or "PotPlayer". Alternatives can be given as "EA app|EA".
        public static App Find(string query)
        {
            var apps = List();
            foreach (var alt in (query ?? "").Split('|'))
            {
                string q = Normalize(alt);
                if (q.Length == 0) continue;
                var words = q.Split(' ');
                App best = null; int bestScore = 0;
                foreach (var a in apps)
                {
                    int score = 0;
                    var aw = a.Norm.Split(' ');
                    if (a.Norm == q) score = 1000;
                    else if (a.Norm.StartsWith(q + " ")) score = 700;
                    else if (words.All(w => aw.Contains(w))) score = 500;
                    else if (a.Norm.Replace(" ", "").Contains(q.Replace(" ", ""))) score = 300;
                    if (score == 0) continue;
                    score -= Math.Min(200, a.Norm.Length); // prefer the shortest (most exact) name
                    if (score > bestScore) { bestScore = score; best = a; }
                }
                if (best != null) return best;
            }
            return null;
        }

        // ------------------------------------------------------------ icons

        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr phbm);
        }

        [StructLayout(LayoutKind.Sequential)] struct SIZE { public int cx; public int cy; }

        [StructLayout(LayoutKind.Sequential)]
        struct BITMAP { public int bmType; public int bmWidth; public int bmHeight; public int bmWidthBytes; public ushort bmPlanes; public ushort bmBitsPixel; public IntPtr bmBits; }

        [StructLayout(LayoutKind.Sequential)]
        struct BITMAPINFOHEADER { public uint biSize; public int biWidth; public int biHeight; public ushort biPlanes; public ushort biBitCount; public uint biCompression; public uint biSizeImage; public int biXPelsPerMeter; public int biYPelsPerMeter; public uint biClrUsed; public uint biClrImportant; }

        [StructLayout(LayoutKind.Sequential)]
        struct DIBSECTION { public BITMAP dsBm; public BITMAPINFOHEADER dsBmih; public uint b0, b1, b2; public IntPtr dshSection; public uint dsOffset; }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);

        [DllImport("gdi32.dll")] static extern int GetObject(IntPtr h, int size, out DIBSECTION ds);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);

        /// The app's real icon as a base64 PNG (size x size), or "" if it can't be read.
        public static string IconPng(string path, int size)
        {
            Bitmap icon = null;
            try { icon = ShellImage(path, 256); } catch (Exception ex) { Program.Log("icon: " + ex.Message); }
            if (icon == null)
            {
                try { using (var ic = Icon.ExtractAssociatedIcon(path)) icon = ic?.ToBitmap(); } catch { }
            }
            if (icon == null) return "";
            using (icon)
            using (var outBmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(outBmp))
                {
                    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    g.SmoothingMode = SmoothingMode.HighQuality;
                    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                    g.Clear(Color.Transparent);
                    g.DrawImage(icon, 0, 0, size, size);
                }
                using (var ms = new MemoryStream())
                {
                    outBmp.Save(ms, ImageFormat.Png);
                    return Convert.ToBase64String(ms.ToArray());
                }
            }
        }

        static Bitmap ShellImage(string path, int px)
        {
            IShellItemImageFactory f;
            SHCreateItemFromParsingName(path, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out f);
            IntPtr hbm;
            int hr = f.GetImage(new SIZE { cx = px, cy = px }, 0x1 | 0x4 /* BIGGERSIZEOK | ICONONLY */, out hbm);
            Marshal.ReleaseComObject(f);
            if (hr != 0 || hbm == IntPtr.Zero) return null;
            try
            {
                DIBSECTION ds;
                if (GetObject(hbm, Marshal.SizeOf(typeof(DIBSECTION)), out ds) == 0 || ds.dsBm.bmBits == IntPtr.Zero || ds.dsBm.bmBitsPixel != 32)
                    return Image.FromHbitmap(hbm);
                int w = ds.dsBm.bmWidth, h = ds.dsBm.bmHeight, stride = ds.dsBm.bmWidthBytes;
                bool bottomUp = ds.dsBmih.biHeight > 0;
                var raw = new byte[stride * h];
                Marshal.Copy(ds.dsBm.bmBits, raw, 0, raw.Length);
                var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
                var bd = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                var row = new byte[w * 4];
                for (int y = 0; y < h; y++)
                {
                    int src = (bottomUp ? h - 1 - y : y) * stride;
                    for (int x = 0; x < w; x++)
                    {
                        int i = src + x * 4, o = x * 4;
                        byte a = raw[i + 3];
                        if (a == 0) { row[o] = row[o + 1] = row[o + 2] = row[o + 3] = 0; continue; }
                        // shell bitmaps are premultiplied: undo it
                        row[o] = (byte)Math.Min(255, raw[i] * 255 / a);
                        row[o + 1] = (byte)Math.Min(255, raw[i + 1] * 255 / a);
                        row[o + 2] = (byte)Math.Min(255, raw[i + 2] * 255 / a);
                        row[o + 3] = a;
                    }
                    Marshal.Copy(row, 0, bd.Scan0 + y * bd.Stride, row.Length);
                }
                bmp.UnlockBits(bd);
                return bmp;
            }
            finally { DeleteObject(hbm); }
        }
    }
}
