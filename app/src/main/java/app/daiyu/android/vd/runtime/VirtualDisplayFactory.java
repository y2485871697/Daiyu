package app.daiyu.android.vd.runtime;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;
import android.view.Surface;

/**
 * 通过公开的 {@link DisplayManager} API 创建虚拟显示器。
 * createVirtualDisplay 会校验包名与调用 UID，因此 root 进程通过
 * {@code ActivityThread.systemMain()} / {@code getSystemContext()} 获取 op package 为 {@code android} 的服务。
 * {@link ImageReader} 及其输出 {@link Surface} 保留到会话结束，显示器回调由框架管理。
 * 公开 API 不能指定 unique id，创建后需从 {@code DisplayInfo} 读回；读不到时报告创建失败。
 */
final class VirtualDisplayFactory {
    /** uid 0 owns this package, which satisfies the display service's package-vs-uid check. */
    static final String OWNER_PACKAGE = "android";

    static final int DEFAULT_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private static final String ACTIVITY_THREAD_CLASS = "android.app.ActivityThread";
    private static final String DISPLAY_MANAGER_GLOBAL_CLASS =
            "android.hardware.display.DisplayManagerGlobal";

    static final class Created {
        final VirtualDisplay display;
        final ImageReader reader;
        final Surface surface;
        final int displayId;
        final String uniqueId;
        final String name;
        final int width;
        final int height;
        final int densityDpi;
        final int flags;

        Created(VirtualDisplay display, ImageReader reader, Surface surface, int displayId,
                String uniqueId, String name, int width, int height, int densityDpi, int flags) {
            this.display = display;
            this.reader = reader;
            this.surface = surface;
            this.displayId = displayId;
            this.uniqueId = uniqueId;
            this.name = name;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.flags = flags;
        }
    }

    private VirtualDisplayFactory() {
    }

    static Created create(String name, int width, int height, int densityDpi, String uniqueId,
            int flags, int maxImages) throws OwnerException {
        if (name == null || name.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "name");
        }
        // The public createVirtualDisplay overload has no caller-supplied unique id; the argument is
        // validated for callers but the actual id is read back from the created display below.
        if (uniqueId == null || uniqueId.isEmpty()) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "uniqueId");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "geometry");
        }

        DisplayManager displayManager = systemDisplayManager();

        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                Math.max(1, maxImages));
        Surface surface = reader.getSurface();
        if (surface == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no surface");
        }

        VirtualDisplay display;
        try {
            display = displayManager.createVirtualDisplay(name, width, height, densityDpi, surface,
                    flags);
        } catch (Throwable ex) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "create:" + ex.getClass().getSimpleName());
        }
        if (display == null) {
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "create result");
        }

        Display bound = display.getDisplay();
        if (bound == null) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "no display");
        }
        int displayId = bound.getDisplayId();
        if (displayId <= 0) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "main display id");
        }
        String actualUniqueId = readBackUniqueId(displayId);
        if (actualUniqueId == null || actualUniqueId.isEmpty()) {
            releaseQuietly(display);
            reader.close();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "unique id");
        }
        return new Created(display, reader, surface, displayId, actualUniqueId, name, width, height,
                densityDpi, flags);
    }

    static void releaseQuietly(VirtualDisplay display) {
        if (display == null) {
            return;
        }
        try {
            display.release();
        } catch (Throwable ignored) {
            // best effort; the process is exiting anyway
        }
    }

    /**
     * The framework's system {@link DisplayManager}, reached the way the platform's own clients
     * reach it: {@code ActivityThread.systemMain()} creates the system thread and
     * {@code getSystemContext()} hands back the context whose op package is {@code android}.
     */
    private static DisplayManager systemDisplayManager() throws OwnerException {
        try {
            Class<?> activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS);
            Method systemMain = activityThreadClass.getMethod("systemMain");
            access(systemMain);
            Object activityThread = systemMain.invoke(null);
            if (activityThread == null) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "system thread");
            }
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            access(getSystemContext);
            Object context = getSystemContext.invoke(activityThread);
            if (!(context instanceof Context)) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "system context");
            }
            Object service = ((Context) context).getSystemService(Context.DISPLAY_SERVICE);
            if (!(service instanceof DisplayManager)) {
                throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY, "display manager");
            }
            return (DisplayManager) service;
        } catch (OwnerException ex) {
            throw ex;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "system context:" + cause.getClass().getSimpleName());
        } catch (Exception ex) {
            throw new OwnerException(OwnerProtocol.ERROR_DISPLAY_NOT_READY,
                    "system context:" + ex.getClass().getSimpleName());
        }
    }

    /** Reads the unique id the display service actually assigned to {@code displayId}. */
    private static String readBackUniqueId(int displayId) {
        try {
            Class<?> globalClass = Class.forName(DISPLAY_MANAGER_GLOBAL_CLASS);
            Method getInstance = globalClass.getMethod("getInstance");
            access(getInstance);
            Object global = getInstance.invoke(null);
            if (global == null) {
                return null;
            }
            Method getInfo = globalClass.getMethod("getDisplayInfo", int.class);
            access(getInfo);
            Object info = getInfo.invoke(global, Integer.valueOf(displayId));
            if (info == null) {
                return null;
            }
            Object fromField = readField(info, "uniqueId");
            if (fromField instanceof String && !((String) fromField).isEmpty()) {
                return (String) fromField;
            }
            try {
                Method method = info.getClass().getMethod("getUniqueId");
                access(method);
                Object value = method.invoke(info);
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // older DisplayInfo exposes the value as a field only
            }
        } catch (Throwable ignored) {
            // fall through to null; the caller refuses to report a requested id as real
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                access(field);
                return field.get(target);
            } catch (Throwable ignored) {
                // parent
            }
        }
        return null;
    }

    private static void access(AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (Throwable ignored) {
            // public members stay callable
        }
    }
}
