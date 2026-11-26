using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace PCRemote
{
    /// Grabs the main screen (with the mouse pointer drawn on it) as a JPEG.
    sealed class ScreenCapture : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)]
        struct CURSORINFO { public int cbSize; public int flags; public IntPtr hCursor; public POINT pt; }
        [StructLayout(LayoutKind.Sequential)]
        struct POINT { public int X; public int Y; }
        [StructLayout(LayoutKind.Sequential)]
        struct ICONINFO { public bool fIcon; public int xHotspot; public int yHotspot; public IntPtr hbmMask; public IntPtr hbmColor; }

        [DllImport("user32.dll")] static extern bool GetCursorInfo(ref CURSORINFO pci);
        [DllImport("user32.dll")] static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);
        [DllImport("user32.dll")] static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hIcon, int cx, int cy, int step, IntPtr brush, int flags);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr o);

        static readonly ImageCodecInfo Jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);

        Bitmap full, scaled;
        readonly MemoryStream ms = new MemoryStream(256 * 1024);

        public int ScreenW { get; private set; }
        public int ScreenH { get; private set; }

        public byte[] Grab(int maxWidth, int quality)
        {
            Rectangle b = Screen.PrimaryScreen.Bounds;
            ScreenW = b.Width; ScreenH = b.Height;
            if (full == null || full.Width != b.Width || full.Height != b.Height)
            {
                full?.Dispose();
                full = new Bitmap(b.Width, b.Height, PixelFormat.Format24bppRgb);
            }
            using (var g = Graphics.FromImage(full))
            {
                g.CopyFromScreen(b.Left, b.Top, 0, 0, b.Size, CopyPixelOperation.SourceCopy);
                DrawCursor(g, b);
            }

            Bitmap outBmp = full;
            if (maxWidth > 0 && b.Width > maxWidth)
            {
                int w = maxWidth, h = (int)Math.Round((double)b.Height * maxWidth / b.Width);
                if (scaled == null || scaled.Width != w || scaled.Height != h)
                {
                    scaled?.Dispose();
                    scaled = new Bitmap(w, h, PixelFormat.Format24bppRgb);
                }
                using (var g = Graphics.FromImage(scaled))
                {
                    g.InterpolationMode = InterpolationMode.Bilinear;
                    g.PixelOffsetMode = PixelOffsetMode.HighSpeed;
                    g.CompositingQuality = CompositingQuality.HighSpeed;
                    g.DrawImage(full, 0, 0, w, h);
                }
                outBmp = scaled;
            }

            ms.SetLength(0);
            using (var ep = new EncoderParameters(1))
            {
                ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)Math.Max(10, Math.Min(95, quality)));
                outBmp.Save(ms, Jpeg, ep);
            }
            return ms.ToArray();
        }

        static void DrawCursor(Graphics g, Rectangle screen)
        {
            try
            {
                var ci = new CURSORINFO { cbSize = Marshal.SizeOf(typeof(CURSORINFO)) };
                if (!GetCursorInfo(ref ci) || ci.flags != 1 /*CURSOR_SHOWING*/) return;
                int hx = 0, hy = 0;
                if (GetIconInfo(ci.hCursor, out var ii))
                {
                    hx = ii.xHotspot; hy = ii.yHotspot;
                    if (ii.hbmMask != IntPtr.Zero) DeleteObject(ii.hbmMask);
                    if (ii.hbmColor != IntPtr.Zero) DeleteObject(ii.hbmColor);
                }
                IntPtr hdc = g.GetHdc();
                try { DrawIconEx(hdc, ci.pt.X - screen.Left - hx, ci.pt.Y - screen.Top - hy, ci.hCursor, 0, 0, 0, IntPtr.Zero, 0x0003 /*DI_NORMAL*/); }
                finally { g.ReleaseHdc(hdc); }
            }
            catch { }
        }

        public void Dispose()
        {
            full?.Dispose(); scaled?.Dispose(); ms.Dispose();
        }
    }
}
