using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PCRemote
{
    static class Program
    {
        public const string RepoOwner = "edward25m";
        public const string RepoName = "pc-remote";
        public static string InstallDir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PCRemote");
        public static string InstalledExe => Path.Combine(InstallDir, "PCRemote.exe");
        public static string Version => Assembly.GetExecutingAssembly().GetName().Version.ToString(3);
        public static TrayApp Tray;

        [STAThread]
        static void Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool created;
            using (var mutex = new Mutex(true, "PCRemote_SingleInstance_v1", out created))
            {
                if (!created)
                {
                    if (Array.IndexOf(args, "--tray") < 0 && Array.IndexOf(args, "--updated") < 0)
                        MessageBox.Show("PC Remote is already running.\nLook for its icon in the system tray (near the clock).", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }

                string me = Assembly.GetExecutingAssembly().Location;
                if (!string.Equals(Path.GetFullPath(me), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase)
                    && Array.IndexOf(args, "--portable") < 0)
                {
                    if (Install(me))
                    {
                        mutex.ReleaseMutex();
                        mutex.Dispose();
                        Process.Start(new ProcessStartInfo(InstalledExe, "--firstrun") { UseShellExecute = true });
                        return;
                    }
                }

                Config.Load();
                bool firstRun = Array.IndexOf(args, "--firstrun") >= 0;
                if (firstRun || !Config.Data.FirewallDone) Firewall.Ensure();

                // Stay responsive even when a game is using the whole PC
                try { Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.AboveNormal; } catch { }
                Tray = new TrayApp(firstRun, Array.IndexOf(args, "--updated") >= 0);
                Application.Run(Tray);
            }
        }

        static bool Install(string me)
        {
            try
            {
                Directory.CreateDirectory(InstallDir);
                // stop a running older copy is not needed: the mutex guarantees we are the only one
                File.Copy(me, InstalledExe, true);
                Autostart.Set(true);
                Shortcut.CreateStartMenu();
                return true;
            }
            catch (Exception ex)
            {
                MessageBox.Show("Could not install PC Remote:\n" + ex.Message + "\n\nIt will run from its current location instead.", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return false;
            }
        }

        public static Icon AppIcon()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("icon.ico"))
                return s != null ? new Icon(s) : SystemIcons.Application;
        }

        public static void Log(string msg)
        {
            try
            {
                Directory.CreateDirectory(Config.Dir);
                string path = Path.Combine(Config.Dir, "log.txt");
                var fi = new FileInfo(path);
                if (fi.Exists && fi.Length > 512 * 1024) fi.Delete();
                File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + msg + Environment.NewLine);
            }
            catch { }
        }
    }

    static class Autostart
    {
        const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public static void Set(bool on)
        {
            using (var k = Registry.CurrentUser.OpenSubKey(Key, true) ?? Registry.CurrentUser.CreateSubKey(Key))
            {
                if (on) k.SetValue("PCRemote", "\"" + Program.InstalledExe + "\" --tray");
                else k.DeleteValue("PCRemote", false);
            }
        }
        public static bool IsOn()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(Key, false))
                return k?.GetValue("PCRemote") != null;
        }
    }

    static class Shortcut
    {
        public static void CreateStartMenu()
        {
            try
            {
                string dir = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
                string lnk = Path.Combine(dir, "PC Remote.lnk");
                Type t = Type.GetTypeFromProgID("WScript.Shell");
                object shell = Activator.CreateInstance(t);
                object sc = t.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnk });
                Type st = sc.GetType();
                st.InvokeMember("TargetPath", BindingFlags.SetProperty, null, sc, new object[] { Program.InstalledExe });
                st.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, sc, new object[] { Program.InstallDir });
                st.InvokeMember("Description", BindingFlags.SetProperty, null, sc, new object[] { "Control this PC from your phone" });
                st.InvokeMember("Save", BindingFlags.InvokeMethod, null, sc, null);
            }
            catch (Exception ex) { Program.Log("shortcut: " + ex.Message); }
        }
    }

    static class Firewall
    {
        // Allows phones on any network type (home Wi-Fi, phone hotspot) to reach PC Remote.
        // Needs one admin approval (the Windows "Yes/No" prompt) the first time.
        public static void Ensure()
        {
            try
            {
                string exe = Program.InstalledExe;
                if (!File.Exists(exe)) exe = Assembly.GetExecutingAssembly().Location;
                string cmd = "/c netsh advfirewall firewall delete rule name=\"PC Remote\" >nul 2>&1 & " +
                             "netsh advfirewall firewall add rule name=\"PC Remote\" dir=in action=allow program=\"" + exe + "\" enable=yes profile=any";
                var psi = new ProcessStartInfo("cmd.exe", cmd) { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden };
                var p = Process.Start(psi);
                p.WaitForExit(20000);
                Config.Data.FirewallDone = true;
                Config.Save();
            }
            catch (Exception ex)
            {
                // user pressed "No" on the admin prompt; Windows will show its own firewall prompt instead
                Program.Log("firewall: " + ex.Message);
                Config.Data.FirewallDone = true;
                Config.Save();
            }
        }
    }
}
