using System;
using System.Runtime.InteropServices;

namespace PCRemote
{
    /// Detects when the window you're controlling (e.g. a game) runs as administrator while PC Remote doesn't.
    /// Windows silently ignores mouse/keyboard from a normal app in that case.
    static class Elevation
    {
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr p, uint access, out IntPtr token);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr t, int cls, out int info, int len, out int ret);

        static readonly bool meElevated = Admin.IsElevated();
        static DateTime lastCheck = DateTime.MinValue;
        static IntPtr lastWindow = IntPtr.Zero;
        static bool lastResult;

        /// True if the window in front is "admin" and we are not (checked at most every 1.5 s).
        public static bool ForegroundBlocked()
        {
            if (meElevated) return false;
            if ((DateTime.Now - lastCheck).TotalMilliseconds < 1500) return lastResult;
            lastCheck = DateTime.Now;
            IntPtr w = GetForegroundWindow();
            if (w == lastWindow) return lastResult;
            lastWindow = w;
            lastResult = false;
            uint pid;
            GetWindowThreadProcessId(w, out pid);
            if (pid == 0) return false;
            IntPtr hp = OpenProcess(0x1000 /*QUERY_LIMITED_INFORMATION*/, false, pid);
            if (hp == IntPtr.Zero) { lastResult = true; return true; } // can't even look at it: it's above us
            try
            {
                IntPtr tok;
                if (!OpenProcessToken(hp, 0x0008 /*TOKEN_QUERY*/, out tok)) { lastResult = true; return true; }
                try
                {
                    int elevated, ret;
                    if (GetTokenInformation(tok, 20 /*TokenElevation*/, out elevated, 4, out ret)) lastResult = elevated != 0;
                }
                finally { CloseHandle(tok); }
            }
            finally { CloseHandle(hp); }
            return lastResult;
        }
    }
}
