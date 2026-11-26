using System;
using System.Collections.Generic;
using System.IO;
using System.Web.Script.Serialization;

namespace PCRemote
{
    public class ConfigData
    {
        public string Id = Guid.NewGuid().ToString("N");
        public string Name = Environment.MachineName;
        public bool FirewallDone;
        public Dictionary<string, string> Allowed = new Dictionary<string, string>();
        public string LastUpdateCheck = "";
    }

    static class Config
    {
        public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PCRemote");
        static string FilePath => Path.Combine(Dir, "config.json");
        public static ConfigData Data = new ConfigData();
        static readonly object Lock = new object();

        public static void Load()
        {
            try
            {
                if (File.Exists(FilePath))
                    Data = Json.Ser.Deserialize<ConfigData>(File.ReadAllText(FilePath)) ?? new ConfigData();
            }
            catch { Data = new ConfigData(); }
            if (Data.Allowed == null) Data.Allowed = new Dictionary<string, string>();
            if (string.IsNullOrEmpty(Data.Id)) Data.Id = Guid.NewGuid().ToString("N");
            if (string.IsNullOrEmpty(Data.Name)) Data.Name = Environment.MachineName;
            Save();
        }

        public static void Save()
        {
            lock (Lock)
            {
                try
                {
                    Directory.CreateDirectory(Dir);
                    File.WriteAllText(FilePath, Json.Ser.Serialize(Data));
                }
                catch (Exception ex) { Program.Log("config save: " + ex.Message); }
            }
        }
    }

    static class Json
    {
        public static readonly JavaScriptSerializer Ser = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        public static Dictionary<string, object> Parse(string s) => Ser.Deserialize<Dictionary<string, object>>(s);
        public static string Str(object o) => Ser.Serialize(o);

        public static string S(this Dictionary<string, object> d, string k, string def = "")
            => d.TryGetValue(k, out var v) && v != null ? Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture) : def;
        public static double D(this Dictionary<string, object> d, string k, double def = 0)
            => d.TryGetValue(k, out var v) && v != null ? Convert.ToDouble(v, System.Globalization.CultureInfo.InvariantCulture) : def;
        public static int I(this Dictionary<string, object> d, string k, int def = 0)
            => d.TryGetValue(k, out var v) && v != null ? Convert.ToInt32(v, System.Globalization.CultureInfo.InvariantCulture) : def;
        public static long L(this Dictionary<string, object> d, string k, long def = 0)
            => d.TryGetValue(k, out var v) && v != null ? Convert.ToInt64(v, System.Globalization.CultureInfo.InvariantCulture) : def;
        public static bool B(this Dictionary<string, object> d, string k, bool def = false)
            => d.TryGetValue(k, out var v) && v is bool b ? b : def;
        public static List<object> A(this Dictionary<string, object> d, string k)
        {
            if (d.TryGetValue(k, out var v) && v is System.Collections.IEnumerable e && !(v is string))
            {
                var l = new List<object>();
                foreach (var x in e) l.Add(x);
                return l;
            }
            return new List<object>();
        }
    }
}
