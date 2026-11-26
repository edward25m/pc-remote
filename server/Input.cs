using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace PCRemote
{
    static class Input
    {
        [StructLayout(LayoutKind.Sequential)]
        struct INPUT { public uint type; public InputUnion U; }

        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        static extern uint MapVirtualKey(uint uCode, uint uMapType);

        const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
        const uint MOUSEEVENTF_MOVE = 0x0001, MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004,
            MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010, MOUSEEVENTF_MIDDLEDOWN = 0x0020,
            MOUSEEVENTF_MIDDLEUP = 0x0040, MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x1000,
            MOUSEEVENTF_ABSOLUTE = 0x8000;
        const uint KEYEVENTF_EXTENDEDKEY = 0x0001, KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004;

        static readonly HashSet<int> Extended = new HashSet<int> {
            0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,0x2D,0x2E, // pgup pgdn end home arrows ins del
            0x5B,0x5C,0x5D, 0x6F, 0x90, 0xA3, 0xA5, 0x2C,      // win, apps, divide, numlock, rctrl, ralt, prtsc
            0xAD,0xAE,0xAF,0xB0,0xB1,0xB2,0xB3                 // volume + media keys
        };

        static void Send(params INPUT[] inputs)
        {
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        static INPUT Mouse(uint flags, int dx = 0, int dy = 0, uint data = 0)
            => new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } } };

        static INPUT Key(ushort vk, bool up)
        {
            uint flags = up ? KEYEVENTF_KEYUP : 0;
            if (Extended.Contains(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
            return new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)MapVirtualKey(vk, 0), dwFlags = flags } } };
        }

        public static void MoveBy(int dx, int dy) => Send(Mouse(MOUSEEVENTF_MOVE, dx, dy));

        /// x, y are 0..1 across the primary screen
        public static void MoveTo(double x, double y)
        {
            int ax = (int)Math.Round(Math.Max(0, Math.Min(1, x)) * 65535);
            int ay = (int)Math.Round(Math.Max(0, Math.Min(1, y)) * 65535);
            Send(Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax, ay));
        }

        public static void Button(string b, string action)
        {
            uint down, up;
            switch (b)
            {
                case "r": down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; break;
                case "m": down = MOUSEEVENTF_MIDDLEDOWN; up = MOUSEEVENTF_MIDDLEUP; break;
                default: down = MOUSEEVENTF_LEFTDOWN; up = MOUSEEVENTF_LEFTUP; break;
            }
            switch (action)
            {
                case "down": Send(Mouse(down)); break;
                case "up": Send(Mouse(up)); break;
                case "dbl": Send(Mouse(down), Mouse(up), Mouse(down), Mouse(up)); break;
                default: Send(Mouse(down), Mouse(up)); break;
            }
        }

        public static void Scroll(int dy, int dx)
        {
            if (dy != 0) Send(Mouse(MOUSEEVENTF_WHEEL, 0, 0, unchecked((uint)dy)));
            if (dx != 0) Send(Mouse(MOUSEEVENTF_HWHEEL, 0, 0, unchecked((uint)dx)));
        }

        public static void Text(string s)
        {
            if (string.IsNullOrEmpty(s)) return;
            var list = new List<INPUT>();
            foreach (char c in s)
            {
                if (c == '\n') { list.Add(Key(0x0D, false)); list.Add(Key(0x0D, true)); continue; }
                if (c == '\b') { list.Add(Key(0x08, false)); list.Add(Key(0x08, true)); continue; }
                list.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE } } });
                list.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } } });
            }
            Send(list.ToArray());
        }

        /// Presses the keys in order and releases them in reverse (a shortcut like Ctrl+Shift+Esc).
        public static void Combo(IList<int> vks)
        {
            if (vks == null || vks.Count == 0) return;
            var list = new List<INPUT>();
            foreach (var vk in vks) list.Add(Key((ushort)vk, false));
            for (int i = vks.Count - 1; i >= 0; i--) list.Add(Key((ushort)vks[i], true));
            Send(list.ToArray());
        }

        public static void KeyDown(int vk) => Send(Key((ushort)vk, false));
        public static void KeyUp(int vk) => Send(Key((ushort)vk, true));
    }
}
