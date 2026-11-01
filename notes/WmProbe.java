import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.*;
import java.io.*;

public class WmProbe {
    static Context ctx;
    public static void main(String[] args) throws Exception {
        System.out.println("uid=" + android.os.Process.myUid() + " sdk=" + android.os.Build.VERSION.SDK_INT);
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        ctx = (Context) getSystemContext.invoke(thread);
        System.out.println("ctx=" + ctx + " pkg=" + ctx.getPackageName());

        // ---- 1. addView with stack trace ----
        for (int type : new int[]{2041, 2039, 2038}) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.type = type;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.format = PixelFormat.RGBA_8888;
                lp.width = 200; lp.height = 200;
                View v = new View(ctx);
                v.setBackgroundColor(0xffff0000);
                wm.addView(v, lp);
                System.out.println("ADD type=" + type + " OK");
            } catch (Throwable t) {
                System.out.println("ADD type=" + type + " FAIL " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(14, st.length); i++) System.out.println("   at " + st[i]);
            }
        }

        // ---- 2. Settings hack via java.lang.reflect.Proxy ----
        try {
            Class<?> iface = Class.forName("android.content.IContentProvider");
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
                (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (m.getDeclaringClass() == Object.class) {
                        if (m.getName().equals("equals")) return p == a[0];
                        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                        return "UnknownContentProvider";
                    }
                    if (m.getName().equals("checkUriPermission")) return 0;
                    if (m.getName().equals("refresh")) return true;
                    if (m.getName().startsWith("canonicalize") || m.getName().startsWith("uncanonicalize")) return a[1];
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    return null;
                });
            Class<?> holderClazz = Class.forName("android.provider.Settings$ContentProviderHolder");
            Field f = holderClazz.getDeclaredField("mContentProvider");
            f.setAccessible(true);
            for (Class<?> c : new Class<?>[]{Settings.Secure.class, Settings.System.class, Settings.Global.class}) {
                Field sc = c.getDeclaredField("sProviderHolder");
                sc.setAccessible(true);
                Object holder = sc.get(null);
                f.set(holder, proxy);
                System.out.println("PROXY installed on " + c.getSimpleName());
            }
            System.out.println("AFTER-HACK Settings.Global animator_scale=" +
                Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f));
            System.out.println("AFTER-HACK Settings.Secure android_id=" +
                Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID));
        } catch (Throwable t) {
            System.out.println("HACK ERR " + t);
            t.printStackTrace(System.out);
        }

        // ---- 3. /dev/input open permission ----
        File dir = new File("/dev/input");
        for (File f : dir.listFiles()) {
            try {
                FileInputStream fis = new FileInputStream(f);
                System.out.println("INPUT_OPEN_OK " + f.getName());
                fis.close();
            } catch (Throwable t) {
                System.out.println("INPUT_OPEN_FAIL " + f.getName() + " " + t);
            }
        }

        System.out.println("PROBE_END");
        System.exit(0);
    }
}
