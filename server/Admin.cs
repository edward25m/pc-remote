using System;
using System.Diagnostics;
using System.IO;
using System.Security.Principal;
using System.Text;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PCRemote
{
    /// "Admin mode": PC Remote starts with Windows as administrator (no prompt), so the phone can control
    /// admin windows (Task Manager, installers) and click "Yes" on UAC prompts once they no longer dim the screen.
    static class Admin
    {
        public const string TaskName = "PC Remote";

        public static bool IsElevated()
        {
            try { return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator); }
            catch { return false; }
        }

        public static bool TaskExists()
        {
            try
            {
                var p = Process.Start(new ProcessStartInfo("schtasks", "/Query /TN \"" + TaskName + "\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true });
                p.WaitForExit(5000);
                return p.ExitCode == 0;
            }
            catch { return false; }
        }

        public static bool UacDims()
        {
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System"))
                    return Convert.ToInt32(k?.GetValue("PromptOnSecureDesktop", 1) ?? 1) != 0;
            }
            catch { return true; }
        }

        public static void Toggle()
        {
            if (IsElevated() || TaskExists()) TurnOff(); else TurnOn();
        }

        static void TurnOn()
        {
            var r = MessageBox.Show(
                "Admin mode lets your phone control everything on this PC, including:\n" +
                "  • admin windows like Task Manager and installers\n" +
                "  • the \"Do you want to allow this app…\" (UAC) prompts\n\n" +
                "To do this, PC Remote will:\n" +
                "  1. start with Windows as administrator (no prompt each time), and\n" +
                "  2. stop UAC prompts from dimming the screen. Windows blocks every remote-control app on the dimmed screen.\n\n" +
                "Note: an undimmed UAC prompt is a little less protected against fake clicks from malicious programs. " +
                "You can turn admin mode off again from this menu.\n\nWindows will ask for permission once. Turn on admin mode?",
                "PC Remote: admin mode", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r != DialogResult.Yes) return;

            try
            {
                string exe = Program.InstalledExe;
                if (!File.Exists(exe)) exe = System.Reflection.Assembly.GetExecutingAssembly().Location;
                string user = WindowsIdentity.GetCurrent().Name;
                string xml = Path.Combine(Path.GetTempPath(), "PCRemote_task.xml");
                File.WriteAllText(xml, TaskXml(exe, user), Encoding.Unicode);
                string cmd = Path.Combine(Path.GetTempPath(), "PCRemote_admin.cmd");
                File.WriteAllText(cmd,
                    "@echo off\r\n" +
                    "schtasks /Create /TN \"" + TaskName + "\" /XML \"" + xml + "\" /F\r\n" +
                    "reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\" /v PromptOnSecureDesktop /t REG_DWORD /d 0 /f\r\n" +
                    "ping 127.0.0.1 -n 3 >nul\r\n" +
                    "schtasks /Run /TN \"" + TaskName + "\"\r\n" +
                    "del \"" + xml + "\"\r\n");
                var p = Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + cmd + "\"") { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden });
                Autostart.Set(false); // the scheduled task starts it from now on
                Environment.Exit(0);   // the admin copy starts in a moment
            }
            catch (Exception ex)
            {
                MessageBox.Show("Admin mode wasn't turned on (" + ex.Message + ").", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        static void TurnOff()
        {
            var r = MessageBox.Show("Turn off admin mode?\n\nPC Remote will start normally with Windows, and UAC prompts will dim the screen again.",
                "PC Remote: admin mode", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r != DialogResult.Yes) return;
            try
            {
                string cmd = Path.Combine(Path.GetTempPath(), "PCRemote_noadmin.cmd");
                File.WriteAllText(cmd,
                    "@echo off\r\n" +
                    "schtasks /Delete /TN \"" + TaskName + "\" /F\r\n" +
                    "reg add \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System\" /v PromptOnSecureDesktop /t REG_DWORD /d 1 /f\r\n");
                var p = Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + cmd + "\"") { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden });
                p.WaitForExit(20000);
                Autostart.Set(true);
                // restart as a normal (non-admin) app: Explorer launches things without admin rights
                string restart = Path.Combine(Path.GetTempPath(), "PCRemote_restart.cmd");
                File.WriteAllText(restart, "@echo off\r\nping 127.0.0.1 -n 3 >nul\r\nstart \"\" \"" + Program.InstalledExe + "\" --tray\r\n");
                Process.Start(new ProcessStartInfo("explorer.exe", "\"" + restart + "\""));
                Environment.Exit(0);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Admin mode wasn't turned off (" + ex.Message + ").", "PC Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        static string Esc(string s) => System.Security.SecurityElement.Escape(s);

        static string TaskXml(string exe, string user) =>
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n" +
            "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\r\n" +
            "  <RegistrationInfo><Description>PC Remote (admin mode)</Description></RegistrationInfo>\r\n" +
            "  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>" + Esc(user) + "</UserId></LogonTrigger></Triggers>\r\n" +
            "  <Principals><Principal id=\"Author\"><UserId>" + Esc(user) + "</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>\r\n" +
            "  <Settings>\r\n" +
            "    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n" +
            "    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n" +
            "    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n" +
            "    <AllowHardTerminate>true</AllowHardTerminate>\r\n" +
            "    <StartWhenAvailable>true</StartWhenAvailable>\r\n" +
            "    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n" +
            "    <Enabled>true</Enabled>\r\n" +
            "    <Hidden>false</Hidden>\r\n" +
            "    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n" +
            "    <Priority>4</Priority>\r\n" +
            "  </Settings>\r\n" +
            "  <Actions Context=\"Author\"><Exec><Command>" + Esc(exe) + "</Command><Arguments>--tray</Arguments></Exec></Actions>\r\n" +
            "</Task>\r\n";
    }
}
